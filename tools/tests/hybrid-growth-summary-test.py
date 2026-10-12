#!/usr/bin/env python3
"""Scope and missing-evidence regressions for the local growth summary."""
import importlib.util
import contextlib
import io
import json
from pathlib import Path
import tempfile
import unittest

SPEC = importlib.util.spec_from_file_location('growth_summary', Path(__file__).parents[1] / 'dev/hybrid-growth-summary.py')
SUMMARY = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(SUMMARY)


class GrowthSummaryTest(unittest.TestCase):
    def test_memory_peak_excludes_post_publication_scan(self):
        with tempfile.TemporaryDirectory() as directory:
            folder = Path(directory)
            (folder / '0').mkdir()
            (folder / '0/resources.jsonl').write_text('\n'.join(json.dumps(row) for row in (
                dict(monotonic=1, rss_bytes=10), dict(monotonic=2, rss_bytes=20),
                dict(monotonic=3, rss_bytes=30), dict(monotonic=4, rss_bytes=999))))
            actual = SUMMARY.bulk_peaks(folder, dict(index=0, handoff=dict(monotonic=2),
                                                    timeline=dict(complete_monotonic=3)))
            self.assertEqual(actual, dict(rss_bytes=30))

    def test_missing_bulk_boundary_does_not_use_whole_process_peak(self):
        actual = SUMMARY.bulk_peaks(Path('/unused'), dict(index=0, resources=dict(rss_bytes=999)))
        self.assertEqual(actual, {})

    def test_smb_peak_includes_upload_and_shared_slice_before_local_handoff(self):
        with tempfile.TemporaryDirectory() as directory:
            folder = Path(directory)
            (folder / '0').mkdir()
            (folder / '0/resources.jsonl').write_text('\n'.join(json.dumps(row) for row in (
                dict(monotonic=1, rss_bytes=10, reference_slice_current_bytes=100),
                dict(monotonic=2, rss_bytes=90, reference_slice_current_bytes=900),
                dict(monotonic=3, rss_bytes=30, reference_slice_current_bytes=300),
                dict(monotonic=4, rss_bytes=999, reference_slice_current_bytes=9999))))
            actual = SUMMARY.bulk_peaks(folder, dict(index=0, handoff=dict(monotonic=3)),
                                        end=3, start=2)
            self.assertEqual(actual, dict(rss_bytes=90, reference_slice_current_bytes=900))

    def test_incomplete_smb_window_does_not_borrow_local_completion_peak(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            folder = root / 'native'
            (folder / '0').mkdir(parents=True)
            (folder / '0/resources.jsonl').write_text(json.dumps(dict(monotonic=2, rss_bytes=999999)))
            (folder / 'report.json').write_text(json.dumps(dict(resource_profile='reference-8g',
                backend='native', smb=True, samples=[dict(index=0, status='RUNNING', profile='document',
                    rows=1000, handoff=dict(monotonic=1, upload_started_monotonic=0),
                    timeline=dict(complete_monotonic=3))])))
            with contextlib.redirect_stdout(io.StringIO()) as output:
                SUMMARY.reference_tables(root)
            self.assertIn('| native | document / 1000 / 1 | RUNNING | — | — | — | — | — | — |',
                          output.getvalue())

    def test_empty_percentile_is_missing_evidence(self):
        self.assertIsNone(SUMMARY.percentile([], .99))
        self.assertEqual(SUMMARY.percentile([30, 10, 20], .95), 30)

    def test_same_fields_do_not_hide_changed_receipt_order(self):
        def sample(digest):
            return dict(input=dict(sha256='same-input'), canonical_oracle=dict(mask='same-fields'),
                        canonical_evidence=dict(deterministic_fingerprints=dict(receipt='same-storage')),
                        qualification=dict(receipt_read_after=dict(payloadSha256=digest, rowsPerScan=10, receiptCount=1)))
        with self.assertRaisesRegex(RuntimeError, 'receipt replay mismatch'):
            SUMMARY.verify_semantics(sample('before'), sample('reordered'))

    def test_reference_mismatch_preserves_individual_results_without_success_ratio(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for backend in ('native', 'staged'):
                folder = root / backend
                folder.mkdir()
                sample = dict(index=0, status='CORRECTNESS_PASS', profile='document', rows=1000,
                    input=dict(sha256='same-input'), canonical_oracle=dict(fields='same'),
                    canonical_evidence=dict(deterministic_fingerprints=dict(aliases=backend)),
                    qualification=dict(receipt_read_after=dict(payloadSha256='same', rowsPerScan=10, receiptCount=1)))
                (folder / 'report.json').write_text(json.dumps(dict(resource_profile='reference-8g',
                    backend=backend, smb=True, samples=[sample])))
            with contextlib.redirect_stdout(io.StringIO()) as output:
                SUMMARY.reference_tables(root)
            text = output.getvalue()
            self.assertIn('NOT_EQUIVALENT_UNDER_EXACT_ORACLE', text)
            self.assertEqual(text.count('CORRECTNESS_PASS'), 2)
            self.assertNotIn('Paired semantic evidence matches', text)

    def test_reference_does_not_pair_different_document_execution_windows(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            sample = dict(index=0, status='CORRECTNESS_PASS', profile='document', rows=1000,
                input=dict(sha256='same-input'), canonical_oracle=dict(fields='same'),
                canonical_evidence=dict(deterministic_fingerprints=dict(aliases='same')),
                qualification=dict(receipt_read_after=dict(payloadSha256='same', rowsPerScan=10, receiptCount=1)))
            for backend in ('native', 'staged'):
                folder = root / backend
                folder.mkdir()
                (folder / 'report.json').write_text(json.dumps(dict(resource_profile='reference-8g',
                    backend=backend, smb=True, single_document_execution=backend == 'staged', samples=[sample])))
            with contextlib.redirect_stdout(io.StringIO()) as output:
                SUMMARY.reference_tables(root)
            self.assertIn('staged (workers/window 1/1)', output.getvalue())
            self.assertNotIn('Paired semantic evidence matches', output.getvalue())


if __name__ == '__main__':
    unittest.main()
