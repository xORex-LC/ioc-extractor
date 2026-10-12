#!/usr/bin/env python3
"""Offline contracts for bounded CAP-6 oracles, resource accounting and fail-closed evidence."""
import importlib.util
import json
from pathlib import Path
import sqlite3
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.dont_write_bytecode = True
SPEC = importlib.util.spec_from_file_location('service_capacity', Path(__file__).resolve().parents[1] / 'dev/service-capacity.py')
CAP = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CAP)
UPGRADE_SPEC = importlib.util.spec_from_file_location('capacity_upgrade', Path(__file__).resolve().parents[1] / 'dev/service-capacity-upgrade.py')
UPGRADE = importlib.util.module_from_spec(UPGRADE_SPEC)
UPGRADE_SPEC.loader.exec_module(UPGRADE)
C5_SPEC = importlib.util.spec_from_file_location('sqlite_capacity', Path(__file__).resolve().parents[1] / 'dev/sqlite-publication-capacity.py')
C5 = importlib.util.module_from_spec(C5_SPEC)
C5_SPEC.loader.exec_module(C5)
NATIVE_COST_SPEC = importlib.util.spec_from_file_location('native_cost_comparison', Path(__file__).resolve().parents[1] / 'dev/native-cost-comparison.py')
NATIVE_COST = importlib.util.module_from_spec(NATIVE_COST_SPEC)
NATIVE_COST_SPEC.loader.exec_module(NATIVE_COST)
ACK_SPEC = importlib.util.spec_from_file_location('projection_ack', Path(__file__).resolve().parents[1] / 'dev/projection-ack-comparison.py')
ACK = importlib.util.module_from_spec(ACK_SPEC)
ACK_SPEC.loader.exec_module(ACK)
JOURNAL_SPEC = importlib.util.spec_from_file_location('typed_journal', Path(__file__).resolve().parents[1] / 'dev/typed-journal-comparison.py')
JOURNAL = importlib.util.module_from_spec(JOURNAL_SPEC)
JOURNAL_SPEC.loader.exec_module(JOURNAL)
RETENTION_SPEC = importlib.util.spec_from_file_location('receipt_retention', Path(__file__).resolve().parents[1] / 'dev/receipt-retention-qualification.py')
RETENTION = importlib.util.module_from_spec(RETENTION_SPEC)
RETENTION_SPEC.loader.exec_module(RETENTION)


def policy():
    columns = {'masks': ['mask', 'url_match', 'host_match', 'source'],
               'ip_list': ['ip', 'score', 'source'], 'hashes': ['hash_md5', 'hash_sha256', 'hash_sha1', 'source'],
               'address_blacklist': ['forbidden_url', 'forbidden_ip'],
               'ioc_aggregate': ['name', 'ip_address', 'url_match', 'host_match', 'hash']}
    keys = {'masks': ['mask'], 'ip_list': ['ip', 'score'], 'hashes': ['hash_md5', 'hash_sha256', 'hash_sha1'],
            'address_blacklist': columns['address_blacklist'], 'ioc_aggregate': columns['ioc_aggregate'][1:]}
    return {'ioc': {'refang': {'rules': [{'from': 'hxxps', 'to': 'https'}, {'from': '[.]', 'to': '.'}, {'from': '[:]', 'to': ':'}]},
                    'sink': {'artifacts': [{'name': name, 'columns': [{'name': column} for column in
                                (['id'] if name in ('masks', 'ip_list', 'hashes') else []) + names]}
                               for name, names in columns.items()]},
                    'artifact-identity': {'artifacts': [{'name': name, 'key-columns': names} for name, names in keys.items()]}}}


class ServiceCapacityTest(unittest.TestCase):
    def test_terminalization_conflicts_are_counted_without_hiding_eventual_success(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            entries = [
                {'error': {'message': 'Document admission changed concurrently'}},
                {'message': 'source ingested', 'event': {'outcome': 'success'}},
                {'error': {'message': 'Document admission changed concurrently'}},
                {'error': {'message': 'Synthetic projection probe'}}]
            (root / 'daemon.log').write_text('startup text\n' + '\n'.join(json.dumps(row) for row in entries))

            self.assertEqual(CAP.admission_terminalization_conflicts(root), 2)

    def test_no_terminalization_conflict_is_inferred_without_a_matching_error(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.assertEqual(CAP.admission_terminalization_conflicts(root), 0)
            (root / 'daemon.log').write_text(json.dumps({'message': 'source ingested'}))
            self.assertEqual(CAP.admission_terminalization_conflicts(root), 0)

    def test_publication_counts_keep_intermediate_slices_and_failed_deliveries_visible(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            database = root / 'var/db/ioc-service.db'
            database.parent.mkdir(parents=True)
            with sqlite3.connect(database) as connection:
                for table in ('export_run', 'publish_ledger'):
                    connection.execute('CREATE TABLE ' + table + '(profile TEXT,status TEXT)')
                connection.executemany('INSERT INTO export_run VALUES (?,?)',
                                      [('lists', 'COMPLETED')] * 3 + [('lists', 'SKIPPED')])
                connection.executemany('INSERT INTO publish_ledger VALUES (?,?)',
                                      [('lists', 'SUCCEEDED')] * 2 + [('lists', 'FAILED')])

            counts = CAP.publication_counts(root)

            self.assertEqual(counts['export_run'], [{'profile': 'lists', 'status': 'COMPLETED', 'count': 3},
                                                   {'profile': 'lists', 'status': 'SKIPPED', 'count': 1}])
            self.assertEqual(counts['publish_ledger'], [{'profile': 'lists', 'status': 'FAILED', 'count': 1},
                                                       {'profile': 'lists', 'status': 'SUCCEEDED', 'count': 2}])

    def test_batch_completion_does_not_accept_only_the_last_finished_document(self):
        from unittest.mock import MagicMock
        config = {'ioc': {'export': {'profiles': [{'name': 'profile', 'artifacts': ['masks']}]}}}
        pending = {'phase': 'PREPARED', 'terminal_outcome': None, 'registration_finalized': 0}
        complete = {'phase': 'TERMINAL', 'terminal_outcome': 'SUCCEEDED', 'registration_finalized': 1}
        with patch.object(CAP, 'admission_rows', side_effect=lambda root, policy, key: [pending if key == 'first' else complete]), \
                patch.object(CAP.BASE, 'rows', return_value=[{'run_id': 'last-run'}]) as rows, \
                patch.object(CAP.time, 'monotonic', side_effect=[0, 0, 2]), patch.object(CAP.time, 'sleep'):
            with self.assertRaisesRegex(RuntimeError, 'Complete local slices timeout'):
                CAP.local_slices(MagicMock(spec=CAP.PrivateUnit), Path('.'), None, config, ('first', 'last'), 1)
        self.assertTrue(all('export_run' not in call.args[1] for call in rows.call_args_list))

    def test_smb_service_completion_is_observed_without_independent_readback(self):
        from types import SimpleNamespace
        from unittest.mock import MagicMock
        share = object.__new__(CAP.INPUTS.PrivateShare)
        share.namespace = '.ioc-cap6-owned'
        share.share = MagicMock()
        unit = MagicMock(spec=CAP.PrivateUnit)
        local = {'profiles': {'profile': {'run': {'run_id': 'slice'}}}}
        base = SimpleNamespace(rows=lambda *args: [{'remote_path': '.ioc-cap6-owned/get/slice',
                                                   'slice_id': 'slice', 'status': 'SUCCEEDED'}])
        observed = share.wait_publications(unit, Path('.'), local, base, 1)
        self.assertEqual(observed['profile']['publication']['status'], 'SUCCEEDED')
        self.assertGreater(observed['profile']['observed_monotonic'], 0)
        share.share.run.assert_not_called()

    def test_smb_completion_rejects_a_successful_ledger_row_outside_the_owned_namespace(self):
        from types import SimpleNamespace
        from unittest.mock import MagicMock
        share = object.__new__(CAP.INPUTS.PrivateShare)
        share.namespace = '.ioc-cap6-owned'
        base = SimpleNamespace(rows=lambda *args: [{'remote_path': '.ioc-cap6-owned-other/get/slice'}])
        with self.assertRaisesRegex(RuntimeError, 'escaped private SMB'):
            share.wait_publications(MagicMock(spec=CAP.PrivateUnit), Path('.'),
                                    {'profiles': {'profile': {'run': {'run_id': 'slice'}}}}, base, 1)

    def test_reference_resource_limits_include_the_collector_and_reserve_host_headroom(self):
        limits = {'cpu.max': '400000 100000', 'memory.high': str(5 * 1024**3),
                  'memory.max': str(6 * 1024**3), 'memory.swap.max': '0'}
        CAP.verify_resource_limits('reference-8g', limits, limits)
        for changes in ({'cpu.max': '200000 100000'}, {'memory.max': str(8 * 1024**3)},
                        {'memory.swap.max': 'max'}):
            with self.subTest(changes=changes), self.assertRaisesRegex(RuntimeError, 'limits differ'):
                CAP.verify_resource_limits('reference-8g', limits, dict(limits, **changes))
        with self.assertRaisesRegex(RuntimeError, 'Missing shared'):
            CAP.verify_resource_limits('reference-8g', limits)

    def test_legacy_resource_identity_stays_fixed_and_reference_cannot_relabel_it(self):
        limits = {'cpu.max': '200000 100000', 'memory.high': str(768 * 1024**2),
                  'memory.max': str(1024**3), 'memory.swap.max': 'max'}
        CAP.verify_resource_limits('legacy', limits)
        with self.assertRaisesRegex(RuntimeError, 'limits differ'):
            CAP.verify_resource_limits('reference-8g', limits, limits)
        with self.assertRaisesRegex(ValueError, 'Unknown capacity'):
            CAP.PrivateUnit(Path('.'), 'unbounded')

    def test_receipt_retention_freeze_excludes_empty_classpath_and_rejects_recursive_output(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            output = root / 'measurements'
            self.assertEqual(RETENTION.classpath_entries('/tmp/helper.jar:', output), ['/tmp/helper.jar'])
            with self.assertRaisesRegex(ValueError, 'contains the output'):
                RETENTION.classpath_entries(str(root) + ':', output)

    def test_receipt_comparison_keeps_modes_separate_and_refuses_changed_payloads(self):
        versioned = dict(payloads='versioned', status='CORRECTNESS_PASS', input={'sha256': 'same'},
                         canonical_oracle={'masks': {'rows': 20}},
                         canonical_evidence={'deterministic_fingerprints': {'receipt': 'same'}},
                         local_window_upper_seconds=2, qualification={'allocatedProcessBytes': 200})
        append = dict(versioned, payloads='append', local_window_upper_seconds=1,
                      qualification={'allocatedProcessBytes': 150})
        self.assertEqual(JOURNAL.compare_pair([versioned, append], 'payloads', ('versioned', 'append'))['allocations_ratio'], .75)
        self.assertNotIn('time_ratio', JOURNAL.compare_pair([versioned, dict(append, status='ERROR')], 'payloads', ('versioned', 'append')))
        with self.assertRaisesRegex(RuntimeError, 'receipt payload'):
            JOURNAL.compare_pair([versioned, dict(append, canonical_evidence={'deterministic_fingerprints': {'receipt': 'lost'}})],
                                 'payloads', ('versioned', 'append'))

    def test_journal_comparison_requires_complete_pairs_identical_semantics_and_receipt_payloads(self):
        row = dict(journal='row', status='CORRECTNESS_PASS', input={'sha256': 'same'},
                   canonical_oracle={'masks': {'rows': 20}}, canonical_evidence={'deterministic_fingerprints': {'receipt': 'same'}},
                   local_window_upper_seconds=2, qualification={'allocatedProcessBytes': 200})
        chunk = dict(row, journal='chunk', local_window_upper_seconds=1, qualification={'allocatedProcessBytes': 100})
        self.assertEqual(JOURNAL.compare_pair([row, chunk])['time_ratio'], .5)
        self.assertNotIn('time_ratio', JOURNAL.compare_pair([row, dict(chunk, status='ERROR')]))
        with self.assertRaisesRegex(RuntimeError, 'receipt payload'):
            JOURNAL.compare_pair([row, dict(chunk, canonical_evidence={'deterministic_fingerprints': {'receipt': 'changed'}})])
        with self.assertRaisesRegex(RuntimeError, 'Incomplete'):
            JOURNAL.compare_pair([row])

    def test_journal_physical_census_does_not_replace_typed_effects_with_pointer_count(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'var/db').mkdir(parents=True)
            with sqlite3.connect(root / 'var/db/ioc-dataframe.db') as db:
                db.executescript("CREATE TABLE publication_version_data(value); INSERT INTO publication_version_data VALUES(1),(2),(3);"
                                 "CREATE TABLE publication_fact(value); INSERT INTO publication_fact VALUES(1);"
                                 "CREATE TABLE publication_chunk(value); INSERT INTO publication_chunk VALUES(1);"
                                 "CREATE TABLE publication_unit(contributions,state); INSERT INTO publication_unit VALUES(3,'COMMITTED');")
            value = JOURNAL.physical_evidence(root)
            self.assertEqual(value['typed_version_rows'], 3)
            self.assertEqual(value['journal_pointer_rows'], 1)
            self.assertEqual(value['committed_effects'], 3)

    def test_journal_bulk_window_has_no_ack_prefix_and_keeps_same_concurrent_control(self):
        with patch.object(JOURNAL, 'physical_evidence', return_value={'rows': 0}), patch.object(JOURNAL.C5.QualificationWindow, 'begin') as begin:
            window = JOURNAL.JournalWindow(Path('.'), 1)
            window.begin()
            begin.assert_called_once_with()
        with patch.object(JOURNAL.C5.QualificationWindow, 'request', return_value={}) as request:
            window.request('control')
            request.assert_called_once_with('ack?pendingOnly=true')

    def test_ack_runtime_cleanup_is_observed_after_owner_exit_and_on_failure(self):
        for fail in (False, True):
            report = {}
            try:
                with ACK.runtime_directory(report) as root:
                    (root / 'owned').write_text('frozen helper')
                    self.assertTrue(root.exists())
                    if fail:
                        raise RuntimeError('qualification failure')
            except RuntimeError as error:
                self.assertTrue(fail)
                self.assertEqual(str(error), 'qualification failure')
            self.assertFalse(root.exists())
            self.assertTrue(report['temporary_runtime_removed'])

    def test_ack_comparison_does_not_publish_ratios_for_failed_windows(self):
        members = [dict(projection='prepared', status='ERROR'), dict(projection='direct', status='CORRECTNESS_PASS')]
        self.assertEqual(ACK.compare_pair(members)['status'], 'INCOMPLETE')
        self.assertNotIn('time_ratio', ACK.compare_pair(members))

    def test_ack_comparison_rejects_different_inputs_or_final_semantics(self):
        prepared = dict(projection='prepared', status='CORRECTNESS_PASS', input={'sha256': 'same'},
                        canonical_oracle={'masks': {'rows': 20}}, local_window_upper_seconds=2,
                        qualification={'allocatedProcessBytes': 200})
        direct = dict(prepared, projection='direct', local_window_upper_seconds=1,
                      qualification={'allocatedProcessBytes': 100})
        self.assertEqual(ACK.compare_pair([prepared, direct])['time_ratio'], .5)
        with self.assertRaisesRegex(RuntimeError, 'provenance differ'):
            ACK.compare_pair([prepared, dict(direct, canonical_oracle={'masks': {'rows': 19}})])
        with self.assertRaisesRegex(RuntimeError, 'inputs'):
            ACK.compare_pair([prepared, dict(direct, input={'sha256': 'different'})])

    def test_ack_tail_estimator_requires_samples_and_uses_nearest_rank(self):
        self.assertEqual(ACK.percentile(range(1, 201), .99), 198)
        with self.assertRaises(ValueError):
            ACK.percentile([], .95)

    def test_clock_preflight_rejects_backward_step_before_any_workload(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            with patch.object(C5.time, 'monotonic_ns', side_effect=[0, 0, 0, 100_000_000, 100_000_000]), \
                 patch.object(C5.time, 'time_ns', side_effect=[10_000_000_000, 7_600_000_000]), \
                 patch.object(C5.time, 'sleep'):
                result = C5.clock_preflight(root)

            self.assertEqual(result['status'], 'UNSTABLE')
            self.assertEqual(result['samples'], 2)
            self.assertEqual(result['minimum_relative_step_upper_ns'], -2_500_000_000)
            self.assertEqual(json.loads((root / 'clock-preflight.json').read_text()), result)
            self.assertEqual(len((root / 'clock-preflight.jsonl').read_text().splitlines()), 2)

    def test_clock_preflight_requires_full_duration_and_brackets_delayed_utc_reads(self):
        with tempfile.TemporaryDirectory() as temporary:
            # The first UTC read takes 400 ms; the next exact read is not a clock step.
            with patch.object(C5.time, 'monotonic_ns', side_effect=[0, 0, 400_000_000, 500_000_000, 500_000_000]), \
                 patch.object(C5.time, 'time_ns', side_effect=[10_400_000_000, 10_500_000_000]), \
                 patch.object(C5.time, 'sleep') as sleep:
                result = C5.clock_preflight(Path(temporary), duration_seconds=.5)

            self.assertEqual(result['status'], 'OBSERVED_STABLE')
            self.assertEqual(result['elapsed_seconds'], .5)
            self.assertEqual(result['minimum_relative_step_upper_ns'], 0)
            sleep.assert_called_once()

    def test_named_wal_files_partition_storage_without_counting_symlinks(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            files = {'var/db/ioc-dataframe.db-wal': 13, 'var/db/ioc-service.db-wal': 17,
                     'var/document-preparation/a/native.db-wal': 19,
                     'var/document-preparation/b/prepared.db-wal': 23,
                     'var/workspaces/c/stage.db-wal': 29, 'dataframe/projected.csv': 31}
            for name, size in files.items():
                path = root / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(b'x' * size)
            (root / 'var/alias-wal').symlink_to(root / 'var/db/ioc-dataframe.db-wal')

            value = CAP.named_storage(root)

            self.assertEqual(value['wal_bytes'], 101)
            self.assertEqual(value['canonical_wal_bytes'], 13)
            self.assertEqual(value['service_wal_bytes'], 17)
            self.assertEqual(value['canonical_private_wal_bytes'], 19)
            self.assertEqual(value['document_private_wal_bytes'], 23)
            self.assertEqual(value['other_wal_bytes'], 29)
            self.assertEqual(value['workspace_bytes'], 71)
            self.assertEqual(value['output_bytes'], 31)
            self.assertEqual(value['wal_files'], {name: size for name, size in files.items() if name.endswith('-wal')})
            (root / 'var/document-preparation/a/native.db-wal').unlink()
            self.assertEqual(CAP.named_storage(root)['wal_bytes'], 82)

    def test_sqlite_http_error_retains_safe_clock_context_and_fails_collection(self):
        import io
        import urllib.error
        from unittest.mock import MagicMock
        body = b'{"utcEpochMs":42,"clockSnapshotAfter":{"status":"UNSAFE"}}'
        error = urllib.error.HTTPError('http://127.0.0.1/control', 500, 'failed', {}, io.BytesIO(body))
        opener = MagicMock()
        opener.open.side_effect = error
        with tempfile.TemporaryDirectory() as temporary:
            window = C5.QualificationWindow(Path(temporary), 18207)
            with patch.object(C5.urllib.request, 'build_opener', return_value=opener):
                window.run()
            with self.assertRaisesRegex(RuntimeError, 'control worker failed'):
                window.finish()
            self.assertIn('Qualification HTTP 500', str(window.failure))
            self.assertIn('clockSnapshotAfter', str(window.failure))
            recorded = json.loads((Path(temporary) / 'controls.jsonl').read_text())
            self.assertEqual(recorded['kind'], 'failure')
            self.assertIn('UNSAFE', recorded['error'])
            self.assertIsNone(window.result)

    def test_sqlite_collector_failed_interval_is_never_a_completed_window(self):
        samples = [dict(backend='native', status='SCREEN_PASS', local_window_upper_seconds=10),
                   dict(backend='staged', status='ERROR', local_window_upper_seconds=120)]

        value = C5.series_screen(samples, 100000, 1)

        self.assertEqual(value['violations'], ['staged_incomplete_or_failed_series'])
        self.assertEqual(value['backends']['native']['median_upper_seconds'], 10)
        self.assertEqual(value['backends']['staged']['completed_windows'], 0)
        self.assertIsNone(value['backends']['staged']['median_upper_seconds'])
        self.assertIsNone(value['backends']['staged']['range_upper_seconds'])

    def test_sqlite_asis_contracts_preserve_csv_source_without_processed_only_binding(self):
        for artifact in CAP.BASE.ARTIFACTS:
            config = policy()
            for identity in config['ioc']['artifact-identity']['artifacts']:
                identity.update(**{'record-key': identity['name'] + '-row-v1', 'match-keys': []})
            config['ioc'].update(**{'dataframe-import': {}, 'export': {'profiles': [{'name': 'p', 'artifacts': CAP.BASE.ARTIFACTS}]}})
            C5.import_policy(config, artifact)
            contract = config['ioc']['dataframe-import']['contracts'][0]
            mapping = contract['artifacts'][0]
            self.assertEqual(contract['mode'], 'as-is')
            self.assertNotIn('source-label-target', mapping)
            if any(column['name'] == 'source' for column in next(
                    item for item in config['ioc']['sink']['artifacts'] if item['name'] == artifact)['columns']):
                self.assertIn({'target': 'source', 'source': 'source'}, mapping['columns'])

    def test_sqlite_docx_pair_has_stable_bytes_and_source_identity(self):
        with tempfile.TemporaryDirectory() as temporary:
            first, second = Path(temporary) / 'first.docx', Path(temporary) / 'second.docx'
            CAP.fixture(first, 61)
            CAP.fixture(second, 61)
            self.assertEqual(first.read_bytes(), second.read_bytes())

    def test_sqlite_processed_fixture_coalesces_distinct_urls_to_independent_host_keys(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            config = policy()
            oracle = CAP.DiskOracle(config, root / 'oracle.db')
            try:
                result = C5.processed_fixture(root / 'import.csv', 100, 'masks', config, oracle, CAP.BASE.digest, 'collapse')
                self.assertEqual((result['source_rows'], result['accepted_rows'], result['duplicate_rows']), (100, 20, 80))
                self.assertEqual(oracle.counts()['masks'], 20)
                values = [json.loads(row[0]) for row in oracle.db.execute('SELECT fields FROM expected')]
                self.assertTrue(all(value['url_match'] == 'u:hAS' and value['host_match'] == 'h:dAS' for value in values))
                self.assertTrue(all('://' not in value['mask'] and '?' not in value['mask'] for value in values))
                self.assertEqual(oracle.db.execute('SELECT count(*) FROM origins').fetchone()[0], 20)
            finally:
                oracle.close()

    def test_sqlite_frozen_runtime_preserves_packaged_application_factories(self):
        import zipfile
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            jar = root / 'packaged.jar'
            with zipfile.ZipFile(jar, 'w') as archive:
                archive.writestr('META-INF/spring.factories', 'strict.preflight=enabled')
                archive.writestr('META-INF/services/java.nio.file.spi.FileSystemProvider', 'boot.loader.only')
                archive.writestr('BOOT-INF/classes/application.yml', 'ioc: {}')
                archive.writestr('BOOT-INF/lib/dependency.jar', b'library')
            helpers = root / 'bootstrap/ioc-app/target/test-classes'
            for package, name in [('com/iocextractor/bootstrap', 'SqliteCapacityApplication'),
                                  ('com/iocextractor/bootstrap', 'SqliteCapacityMetrics'),
                                  ('com/iocextractor/bootstrap', 'ReceiptPayloadDiagnostics'),
                                  ('com/iocextractor/adapter/out/store/jdbc', 'StagedCapacityBundle')]:
                path = helpers / package / (name + '.class')
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(b'compiled qualification helper')
            (helpers / 'unrelated.class').write_bytes(b'excluded')
            with patch.object(C5, 'REPO', root):
                C5.freeze_runtime(jar, root / 'frozen')
            self.assertEqual((root / 'frozen/classes/META-INF/spring.factories').read_text(), 'strict.preflight=enabled')
            self.assertFalse((root / 'frozen/classes/META-INF/services/java.nio.file.spi.FileSystemProvider').exists())
            self.assertFalse((root / 'frozen/classes/unrelated.class').exists())

    def test_sqlite_control_collector_failure_cannot_publish_successful_metrics(self):
        with tempfile.TemporaryDirectory() as temporary:
            window = C5.QualificationWindow(Path(temporary), 18207)
            with patch.object(window, 'request', side_effect=OSError('loopback failed')) as request:
                window.run()
                with self.assertRaisesRegex(RuntimeError, 'control worker failed'):
                    window.finish()
                incomplete = window.failure_summary()
                self.assertEqual(incomplete['status'], 'INCOMPLETE')
                self.assertEqual(incomplete['worker_failure_type'], 'OSError')
                self.assertEqual(incomplete['worker_failure'], 'loopback failed')
                self.assertIsNone(window.result)
                self.assertEqual([call.args[0] for call in request.call_args_list], ['control'])

    def test_sqlite_failed_pairs_preserve_semantic_comparison_and_both_failures(self):
        def sample(backend, status, seconds):
            return dict(pair=0, backend=backend, status=status, input={'sha256': 'same-input'},
                        canonical_oracle={'rows': 10}, local_window_upper_seconds=seconds,
                        qualification={'allocatedProcessBytes': 100})
        native, staged = sample('native', 'FAIL', 100), sample('staged', 'FAIL', 120)
        result = C5.compare([native, staged])[0]
        self.assertEqual((result['native_status'], result['staged_status']), ('FAIL', 'FAIL'))
        self.assertEqual(result['staged_native_time_ratio'], 1.2)
        staged['input']['sha256'] = 'different'
        with self.assertRaisesRegex(RuntimeError, 'inputs or independent canonical'):
            C5.compare([native, staged])

    def test_sqlite_window_replaces_only_cumulative_admission_bounds(self):
        native = {'violations': ['rss_bytes', 'writer_hold_unresolved'], 'status': 'FAIL'}
        sample = {'qualification': {'admissionScope': 'canonical_bean_only',
                  'writerOperations': {'CONTROL': {'maximumHoldNanos': 1, 'maximumWaitNanos': 11e9}}}}
        with patch.object(C5.CAP, 'gate_sample', return_value=native):
            value = C5.gates(sample, 100000)
        self.assertEqual(value['violations'], ['rss_bytes', 'control_wait'])
        self.assertTrue(value['admission_window_maxima_exact'])

    def test_sqlite_series_cannot_pass_from_individually_fast_or_incomplete_samples(self):
        samples = [dict(backend=backend, status='SCREEN_PASS', local_window_upper_seconds=seconds)
                   for backend, seconds in [('native', 10), ('staged', 31)]]
        value = C5.series_screen(samples, 100000, 1)
        self.assertEqual(value['violations'], ['staged_median_local_window'])
        self.assertEqual(value['status'], 'FAIL')
        self.assertEqual(C5.series_screen(samples, 1000000, 1)['status'], 'SCREEN_PASS')
        value = C5.series_screen(samples, 100000, 3)
        self.assertEqual(value['violations'], ['native_incomplete_or_failed_series', 'staged_incomplete_or_failed_series'])
        samples[1].update(status='FAIL', local_window_upper_seconds=None)
        self.assertEqual(C5.series_screen(samples, 100000, 1)['violations'], ['staged_incomplete_or_failed_series'])

    def test_native_cost_series_preserves_failed_screens_without_accepting_capacity(self):
        sample = dict(backend='native', status='FAIL', local_window_upper_seconds=70,
                      qualification={'admissionWindowMaximaExact': True, 'admissionScope': 'canonical_bean_only'},
                      canonical_oracle={'rows': 10}, canonical_evidence={'family_rows': {}},
                      temporary_state_removed=True, cleanup_errors=[])
        result = C5.native_cost_series([sample], 1)
        self.assertEqual(result['status'], 'COMPLETE')
        self.assertEqual(result['screen_statuses'], ['FAIL'])
        self.assertTrue(result['acceptance'].startswith('NOT_ACCEPTED'))
        self.assertEqual(C5.native_cost_series([sample], 3)['status'], 'INCOMPLETE')
        for change in ({'status': 'ERROR'}, {'qualification': {}}, {'canonical_oracle': None},
                       {'canonical_evidence': None}, {'cleanup_errors': ['cannot stop']},
                       {'temporary_state_removed': False}, {'backend': 'staged'}):
            self.assertEqual(C5.native_cost_series([dict(sample, **change)], 1)['status'], 'INCOMPLETE')

    def test_native_cost_fingerprints_detect_identity_rank_and_receipt_changes(self):
        import sqlite3
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            database = root / 'var/db/ioc-dataframe.db'
            database.parent.mkdir(parents=True)
            with sqlite3.connect(database) as db:
                db.executescript('''
                    CREATE TABLE masks(row_key TEXT, id INTEGER, _lifecycle_id INTEGER);
                    CREATE TABLE masks_sources(row_id INTEGER);
                    CREATE TABLE masks_receipt_rows(ordinal INTEGER,row_key TEXT,mask TEXT);
                    CREATE TABLE canonical_match_alias(artifact,definition_id,key_hash,key_canonical,lifecycle_id,canonical_row_id);
                    CREATE TABLE canonical_lifecycle_field_origin(artifact,lifecycle_id,field_name,admission_order,occurrence_position,occurrence_id);
                    CREATE TABLE confirmation_receipt(state,expected_artifacts,row_count,payload_version);
                    CREATE TABLE confirmation_receipt_field_position(artifact,ordinal,field_name,occurrence_position);
                    CREATE TABLE registered_observation(admission_order,origin_kind,occurrence_id);
                    INSERT INTO masks VALUES('key',1,1);
                    INSERT INTO masks_receipt_rows VALUES(0,'key','example.org');
                    INSERT INTO canonical_lifecycle_field_origin VALUES('masks',1,'mask',1,4,'private');
                ''')
            config = {'ioc': {'sink': {'artifacts': [{'name': 'masks', 'columns': [{'name': 'mask'}]}]}}}
            with patch.object(C5.CAP.BASE, 'ARTIFACTS', ['masks']):
                before = C5.native_cost_evidence(root, config)['deterministic_fingerprints']
                with sqlite3.connect(database) as db:
                    db.execute("UPDATE canonical_lifecycle_field_origin SET occurrence_id='other-private'")
                self.assertEqual(C5.native_cost_evidence(root, config)['deterministic_fingerprints'], before)
                with sqlite3.connect(database) as db:
                    db.execute('UPDATE masks SET id=2')
                    db.execute('UPDATE canonical_lifecycle_field_origin SET occurrence_position=5')
                    db.execute("UPDATE masks_receipt_rows SET mask='changed.org'")
                after = C5.native_cost_evidence(root, config)['deterministic_fingerprints']
                for name in ['masks_identities', 'field_origins', 'masks_receipt_payload']:
                    self.assertNotEqual(before[name], after[name])

    def test_native_cost_comparison_requires_equal_semantics_and_preserves_negative_screens(self):
        import copy
        peaks = {name: 100 for name in ['heap_used_bytes', 'rss_bytes', 'non_file_bytes', 'workspace_bytes', 'canonical_wal_bytes']}
        sample = dict(backend='native', status='FAIL', failure=None, cleanup_errors=[], temporary_state_removed=True,
                      input={'sha256': 'physical-input'}, canonical_oracle={'rows': 10},
                      canonical_evidence={'deterministic_fingerprints': {'identities': {'sha256': 'key/id/rank'}}},
                      runtime={'effective_limits': {'cpu.max': '200000 100000'}, 'flags': ['-Xmx512m']},
                      gates={'violations': ['writer_hold']}, local_window_upper_seconds=70,
                      qualification={'admissionWindowMaximaExact': True, 'allocatedProcessBytes': 1000,
                                     'writerOperations': {'PROMOTION': {'maximumHoldNanos': 13e9},
                                                          'CONTROL': {'maximumWaitNanos': 12e9}}},
                      resources={'process_cpu_seconds': 60, 'peaks': peaks})
        before = dict(rows=100, format='docx', shape='unique', source_commit='before-source', jar_sha256='before-jar',
                      policy_sha256='policy', driver_sha256='driver', service_driver_sha256='service-driver',
                      helper_class_sha256={'helper': 'class-digest'}, java='21',
                      native_cost_cycle=True, mode='instrumented_primary', status='COMPLETE',
                      cost_series={'status': 'COMPLETE'}, requested_native_windows=1, samples=[sample])
        after = copy.deepcopy(before)
        after['samples'][0]['qualification']['allocatedProcessBytes'] = 700
        result = NATIVE_COST.compare(before, after)
        self.assertEqual(result['metrics']['allocated_process_bytes']['after_before_median_ratio'], 0.7)
        self.assertEqual(result['screen_statuses']['after'], ['FAIL'])
        self.assertEqual(result['screen_violations']['after'], [['writer_hold']])
        self.assertTrue(result['acceptance'].startswith('NOT_ACCEPTED'))
        for field, value in [('status', 'ERROR'), ('requested_native_windows', 2),
                             ('mode', 'diagnostic'), ('policy_sha256', 'other-policy'),
                             ('source_commit', None), ('samples', [])]:
            changed = copy.deepcopy(after)
            changed[field] = value
            with self.assertRaises(ValueError):
                NATIVE_COST.compare(before, changed)
        for field, value in [('input', {'sha256': 'other-input'}),
                             ('canonical_evidence', {'deterministic_fingerprints': {'identities': {'sha256': 'different-id'}}}),
                             ('cleanup_errors', ['not stopped']), ('runtime', {'effective_limits': {}, 'flags': []})]:
            changed = copy.deepcopy(after)
            changed['samples'][0][field] = value
            with self.assertRaises(ValueError):
                NATIVE_COST.compare(before, changed)

    def test_upgrade_startup_failure_stops_unit_before_state_removal(self):
        from types import SimpleNamespace
        from unittest.mock import MagicMock
        with tempfile.TemporaryDirectory() as temporary:
            repo = Path(temporary)
            (repo / '.dev').mkdir()
            config = policy()
            config['ioc']['processing'] = {}
            source = repo / 'config.yml'
            source.write_text(CAP.yaml.safe_dump(config))
            jar = repo / 'runtime.jar'
            jar.write_text('frozen executable')
            args = SimpleNamespace(previous=jar, candidate=jar, config=source, output=repo / '.dev/evidence')
            unit = MagicMock()
            def start(executable, policy):
                self.assertEqual(policy.name, 'previous.yml')
                self.assertFalse((policy.parent / 'application.yml').exists())
                self.assertTrue((policy.parent / 'candidate.yml').is_file())
                self.assertNotIn('workspace', CAP.yaml.safe_load(policy.read_text())['ioc']['processing'])
                return {}
            unit.start.side_effect = start
            def stop():
                root = next((repo / '.dev').glob('ioc-cap6-upgrade-*'))
                self.assertTrue((root / 'candidate.jar').exists())
                self.assertTrue((root / 'oracle.db').exists())
                (root / 'daemon.log').write_text('startup failure evidence')
                return {'process_terminated': True}
            unit.close.side_effect = stop
            def private(source, root, port):
                (root / 'application.yml').write_text(source.read_text())
                return config
            def git(arguments):
                return 'head' if 'rev-parse' in arguments else ''
            with patch.object(UPGRADE.CAP, 'REPO', repo), patch.object(UPGRADE.CAP, 'command', side_effect=git), \
                    patch.object(UPGRADE.CAP, 'private_config', side_effect=private), \
                    patch.object(UPGRADE.CAP, 'PrivateUnit', return_value=unit), \
                    patch.object(UPGRADE.CAP, 'ready', side_effect=RuntimeError('readiness failure')):
                report = UPGRADE.rehearse(args)
            self.assertEqual(report['status'], 'ERROR')
            self.assertTrue(report['temporary_state_removed'])
            unit.close.assert_called_once()
            self.assertEqual(list((repo / '.dev').glob('ioc-cap6-upgrade-*')), [])
            self.assertTrue((args.output / 'report.json').is_file())
            self.assertEqual((args.output / 'previous-failure.log').read_text(), 'startup failure evidence')

    def test_stopped_backup_hashes_detect_journal_or_output_mutation(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            path = root / 'db-journal'
            path.write_bytes(b'original')
            before = UPGRADE.snapshot_files(root)
            path.write_bytes(b'changed')
            self.assertNotEqual(before, UPGRADE.snapshot_files(root))

    def test_post_gc_heap_includes_both_serial_generations(self):
        serial = ' def new generation total 62464K, used 44K\n tenured generation total 138564K, used 49494K\n Metaspace used 86737K'
        self.assertEqual(CAP.live_heap_bytes(serial), 49538 * 1024)
        self.assertEqual(CAP.live_heap_bytes(' garbage-first heap total 131072K, used 42K'), 42 * 1024)
        with self.assertRaisesRegex(RuntimeError, 'Missing post-GC'):
            CAP.live_heap_bytes('unavailable')

    def test_window_maximum_does_not_reuse_population_maximum(self):
        before = {'PROMOTION': {'completed': 10, 'totalHoldNanos': 25000000000, 'maximumHoldNanos': 13000000000,
                               'totalWaitNanos': 100, 'maximumWaitNanos': 50}}
        after = {'PROMOTION': dict(before['PROMOTION'], completed=15, totalHoldNanos=26500000000)}
        value = CAP.writer_window(before, after)['PROMOTION']
        self.assertEqual(value['maximumHoldNanosUpperBound'], 1500000000)
        self.assertFalse(value['maximumHoldNanosIsExact'])
        after['PROMOTION'].update(totalHoldNanos=40000000000, maximumHoldNanos=15000000000)
        self.assertTrue(CAP.writer_window(before, after)['PROMOTION']['maximumHoldNanosIsExact'])
        after['PROMOTION']['completed'] = 9
        with self.assertRaisesRegex(RuntimeError, 'regressed'):
            CAP.writer_window(before, after)

    def test_absolute_operator_paths_are_privatized_without_changing_limits(self):
        config = next(CAP.yaml.safe_load_all((CAP.REPO / 'bootstrap/ioc-app/src/main/resources/application.yml').read_text()))
        ioc = config['ioc']
        ioc['ingestion']['ledger']['path'] = '/srv/shared/ledger'
        ioc['processing']['workspace']['directory'] = '/srv/shared/workspace'
        ioc['sink']['artifacts'][0]['path'] = '/srv/shared/operator.csv'
        limits = dict(ioc['processing']['workspace'])
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source = root / 'input.yml'
            source.write_text(CAP.yaml.safe_dump(config))
            private = CAP.private_config(source, root, 18206)['ioc']
            self.assertEqual(private['ingestion']['ledger']['path'], './var/ledger')
            self.assertEqual(private['sink']['artifacts'][0]['path'], './dataframe/operator.csv')
            self.assertEqual({key: value for key, value in private['processing']['workspace'].items() if key != 'directory'},
                             {key: value for key, value in limits.items() if key != 'directory'})
            self.assertTrue(all(value.startswith('./var/import/') for value in private['dataframe-import']['runtime']['dirs'].values()))

    def test_missing_diagnostic_launcher_uses_the_current_jdk_modules(self):
        with patch.object(CAP.shutil, 'which', side_effect=lambda name: '/usr/bin/java' if name == 'java' else None):
            self.assertEqual(CAP.jdk_tool('jstat'), ['/usr/bin/java', '-m', 'jdk.jcmd/sun.tools.jstat.Jstat'])

    def test_jvm_counters_use_bytes_without_double_counting_class_space(self):
        header = 'S0U S1U EU OU S0C S1C EC OC MU MC CCSU CCSC YGC FGC GCT CGC CGCT'
        with patch.object(CAP, 'command', return_value=header + '\n1 2 3 4 10 20 30 40 50 60 7 8 9 0 1.5 - -'):
            value = CAP.jvm_sample(42)
            self.assertEqual(value['heap_used_bytes'], 10 * 1024)
            self.assertEqual(value['heap_committed_bytes'], 100 * 1024)
            self.assertEqual(value['metaspace_used_bytes'], 50 * 1024)
            self.assertEqual(value['gc']['GCT'], 1.5)
        with patch.object(CAP, 'command', return_value='EU OU\n1 2'):
            with self.assertRaisesRegex(RuntimeError, 'Missing JDK'):
                CAP.jvm_sample(42)

    def test_all_five_import_fixtures_preserve_case_paths_and_duplicate_slot_hole(self):
        config = policy()
        config['ioc']['dataframe-import'] = {'contracts': [
            {'id': spec['name'], 'mode': 'as-is', 'artifacts': [{'name': spec['name']}],
             'recognition': {'required-columns': [item['name'] for item in spec['columns']]}}
            for spec in config['ioc']['sink']['artifacts']]}
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            oracle = CAP.DiskOracle(config, root / 'oracle.db')
            try:
                for artifact in CAP.BASE.ARTIFACTS:
                    path = root / (artifact + '.csv')
                    manifest = CAP.INPUTS.import_fixture(path, 10, artifact, config, oracle, CAP.BASE.digest)
                    self.assertEqual(manifest['accepted_rows'], 10)
                    self.assertEqual(oracle.counts()[artifact], 10)
                self.assertIn('https://Preserved-1.Example.test:9090/api?q=1', (root / 'masks.csv').read_text())
                rows = (root / 'ip_list.csv').read_text().splitlines()
                self.assertTrue(rows[-1].startswith('900011;'))
                self.assertIn('Ignored duplicate', rows[-1])
                kept = [json.loads(row[0]) for row in oracle.db.execute("SELECT fields FROM expected WHERE artifact='ip_list'")]
                self.assertEqual({row['source'] for row in kept}, {'Original Case'})
            finally:
                oracle.close()

    def test_html_and_real_docx_have_equal_fields_keys_and_winners(self):
        with tempfile.TemporaryDirectory() as temporary:
            root, outputs = Path(temporary), []
            for suffix in ('html', 'docx'):
                path = root / ('fixture.' + suffix)
                manifest = CAP.fixture(path, 1000)
                oracle = CAP.DiskOracle(policy(), root / (suffix + '.db'))
                try:
                    CAP.feed_fixture(oracle, path)
                    self.assertEqual(oracle.observations, 1000)
                    self.assertEqual(manifest['sections'], 4)
                    outputs.append(list(oracle.db.execute('SELECT * FROM expected ORDER BY artifact,key')))
                    self.assertGreater(oracle.counts()['ioc_aggregate'], 500)
                finally:
                    oracle.close()
            self.assertEqual(outputs[0], outputs[1])

    def test_keep_first_and_last_nonempty_complete_keys_are_independent(self):
        with tempfile.TemporaryDirectory() as temporary:
            oracle = CAP.DiskOracle(policy(), Path(temporary) / 'oracle.db')
            try:
                oracle.source_key = 'document-sha'
                oracle.add('ip_list', {'ip': '192.0.2.1', 'source': 'first'})
                oracle.add('ip_list', {'ip': '192.0.2.1', 'source': 'last'})
                oracle.add('ip_list', {'ip': '192.0.2.1', 'score': '20', 'source': 'distinct'})
                oracle.add('ioc_aggregate', {'host_match': 'example.org', 'name': 'first'}, last=True)
                oracle.add('ioc_aggregate', {'host_match': 'example.org', 'name': 'last'}, last=True)
                rows = [json.loads(row[0]) for row in oracle.db.execute("SELECT fields FROM expected WHERE artifact='ip_list'")]
                self.assertEqual({row['source'] for row in rows}, {'first', 'distinct'})
                aggregate = json.loads(oracle.db.execute("SELECT fields FROM expected WHERE artifact='ioc_aggregate'").fetchone()[0])
                self.assertEqual(aggregate['name'], 'last')
            finally:
                oracle.close()

    def test_streamed_csv_rejects_corruption_missing_rows_duplicates_and_slot_changes(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            oracle = CAP.DiskOracle(policy(), root / 'oracle.db')
            try:
                oracle.source_key = 'doc'
                oracle.add('ip_list', {'ip': '192.0.2.1', 'source': 'first'})
                key = oracle.db.execute("SELECT key FROM expected WHERE artifact='ip_list'").fetchone()[0]
                with sqlite3.connect(root / 'actual.db') as actual:
                    actual.executescript('CREATE TABLE ip_list(row_key TEXT,_lifecycle_id INTEGER);'
                        'CREATE TABLE export_slot_assignment(profile TEXT,artifact TEXT,lifecycle_id INTEGER,slot INTEGER);')
                    actual.execute('INSERT INTO ip_list VALUES(?,1)', (key,))
                    actual.execute("INSERT INTO export_slot_assignment VALUES('p','ip_list',1,60)")
                valid = 'id;ip;score;source\n60;192.0.2.1;NULL;first\n'
                path = root / 'public.csv'
                path.write_text(valid)
                oracle.check_csv('ip_list', path, root / 'actual.db', 'p')
                for value, error in ((valid.replace('first', 'wrong'), 'fields/key'),
                                     ('id;ip;score;source\n', 'missing rows'),
                                     ('ip;score;source\n192.0.2.1;NULL;first\n', 'schema'),
                                     ('id;source;score;ip\n60;first;NULL;192.0.2.1\n', 'schema'),
                                     (valid + valid.splitlines()[1] + '\n', 'Duplicate'),
                                     (valid.replace('60;', '61;'), 'slot/registry')):
                    path.write_text(value)
                    with self.assertRaisesRegex(RuntimeError, error):
                        oracle.check_csv('ip_list', path, root / 'actual.db', 'p')
                path.write_text(valid)
                with sqlite3.connect(root / 'actual.db') as actual:
                    actual.execute('DELETE FROM export_slot_assignment')
                with self.assertRaisesRegex(RuntimeError, 'slot/registry'):
                    oracle.check_csv('ip_list', path, root / 'actual.db', 'p')
                with sqlite3.connect(root / 'actual.db') as actual:
                    actual.executemany("INSERT INTO export_slot_assignment VALUES('p','ip_list',1,?)", [(60,), (61,)])
                with self.assertRaisesRegex(RuntimeError, 'Duplicate canonical'):
                    oracle.check_csv('ip_list', path, root / 'actual.db', 'p')
            finally:
                oracle.close()

    def test_microsecond_psi_counters_and_regression_rejection(self):
        before = CAP.pressure('some avg10=0.00 total=100\nfull avg10=0.00 total=10')
        after = CAP.pressure('some avg10=0.00 total=200\nfull avg10=0.00 total=60')
        self.assertEqual(CAP.difference(before, after), {'some': 100, 'full': 50})
        with self.assertRaisesRegex(RuntimeError, 'regressed'):
            CAP.difference(after, before)
        with self.assertRaisesRegex(RuntimeError, 'Missing'):
            CAP.difference(before, {'some': 100})

    def test_fast_completion_cannot_hide_long_writer_hold_or_oom(self):
        sample = {'local_window_upper_seconds': 1, 'resources': {'peaks': {'rss_bytes': 100, 'non_file_bytes': 100},
                   'psi_full_fraction': 0, 'memory_events_delta': {'oom': 0, 'oom_kill': 0}},
                  'health_before': {'writerOperations': {}},
                  'health': {'writerOperations': {'PROMOTION': {'completed': 1, 'maximumHoldNanos': 6000000000,
                    'totalHoldNanos': 6000000000, 'maximumWaitNanos': 0, 'totalWaitNanos': 0}}}}
        self.assertEqual(CAP.gate_sample(sample, 100000)['violations'], ['writer_hold'])
        sample['resources']['memory_events_delta']['oom_kill'] = 1
        self.assertIn('oom', CAP.gate_sample(sample, 100000)['violations'])

    def test_failed_sampler_cannot_publish_partial_success(self):
        with tempfile.TemporaryDirectory() as temporary:
            with patch.object(CAP.ResourceSampler, 'read', side_effect=[{'monotonic': 1}, OSError('synthetic failure')]):
                sampler = CAP.ResourceSampler(1, Path(temporary), Path(temporary))
                sampler.worker.join(2)
                self.assertFalse(sampler.worker.is_alive())
                with self.assertRaisesRegex(RuntimeError, 'sampler failed'):
                    sampler.summary()
                with self.assertRaisesRegex(RuntimeError, 'sampler failed'):
                    sampler.close()

    def test_atomic_handoff_uses_completion_anchor_not_old_input_mtime(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / 'var/inbox').mkdir(parents=True)
            source = root / 'input.html'
            source.write_text('fixture')
            import os
            os.utime(source, (1, 1))
            anchor = CAP.local_handoff(source, root)
            self.assertGreater(anchor['epoch_seconds'], 1)
            self.assertEqual((root / 'var/inbox/input.html').read_text(), 'fixture')
            self.assertFalse((root / 'var/inbox/input.html.part').exists())

    def test_startup_failure_stops_owned_unit_before_removing_private_state(self):
        from types import SimpleNamespace
        from unittest.mock import MagicMock
        with tempfile.TemporaryDirectory() as temporary:
            repo = Path(temporary)
            (repo / '.dev').mkdir()
            evidence = repo / 'evidence'
            evidence.mkdir()
            args = SimpleNamespace(config=repo / 'policy.yml', port=18206, diagnostic=False)
            args.config.write_text('policy')
            unit = MagicMock()
            unit.start.return_value = {}
            order = []
            def stop():
                state = next((repo / '.dev').iterdir())
                self.assertTrue((state / 'oracle.db').exists())
                order.append('stopped')
                return {'process_terminated': True}
            unit.close.side_effect = stop
            with patch.object(CAP, 'REPO', repo), patch.object(CAP, 'PrivateUnit', return_value=unit), \
                    patch.object(CAP, 'private_config', return_value=policy()), \
                    patch.object(CAP.BASE, 'digest', return_value='sha'), \
                    patch.object(CAP, 'ready', side_effect=RuntimeError('readiness failure')):
                report = {'samples': []}
                value = CAP.sample(args, repo / 'jar', report, 0, evidence)
            self.assertEqual(order, ['stopped'])
            self.assertEqual(value['status'], 'ERROR')
            self.assertTrue(value['temporary_state_removed'])
            self.assertEqual(list((repo / '.dev').iterdir()), [])
            self.assertEqual(json.loads((evidence / 'report.json').read_text())['samples'][0]['failure'], 'readiness failure')

    def test_file_ledger_terminal_anchor_preserves_the_configured_journal_backend(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            directory = root / 'var/ledger/document-admission'
            directory.mkdir(parents=True)
            (directory / 'fixture.properties').write_text('#ioc document admission\n'
                'sourceKey=sha\nobservationId=occurrence\nphase=TERMINAL\n'
                'createdAt=2026-10-07T13\\:52\\:21.331827039Z\n'
                'updatedAt=2026-10-07T13\\:52\\:22.123Z\n'
                'terminalOutcome=SUCCEEDED\nregistrationFinalized=true\n')
            config = {'ioc': {'ingestion': {'ledger': {'type': 'file', 'path': './var/ledger'}}}}
            rows = CAP.admission_rows(root, config, 'sha')
            self.assertEqual(rows[0]['registration_finalized'], 1)
            self.assertEqual(rows[0]['terminal_outcome'], 'SUCCEEDED')
            self.assertGreater(rows[0]['updated_at_ms'], rows[0]['created_at_ms'])
            self.assertEqual(CAP.admission_rows(root, config, 'other'), [])

    def test_worker_oom_fails_even_when_the_jvm_unit_is_active(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            unit = CAP.PrivateUnit(root)
            log = root / 'daemon.log'
            log.write_text('unrelated startup\njava.lang.OutOf')
            with patch.object(unit, 'properties', return_value={'ActiveState': 'active'}):
                unit.assert_running()
                with log.open('a') as contents:
                    contents.write('MemoryError: Java heap space\n')
                with self.assertRaisesRegex(CAP.WorkloadFailure, 'OutOfMemoryError'):
                    unit.assert_running()

    def test_workspace_full_stops_the_failed_capacity_screen_before_retry(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / 'daemon.log').write_text('org.sqlite.SQLiteException: [SQLITE_FULL] database is full')
            with self.assertRaisesRegex(CAP.WorkloadFailure, 'capacity exhausted'):
                CAP.PrivateUnit(root).assert_running()

    def test_evidence_copy_failure_still_removes_terminated_private_state(self):
        from types import SimpleNamespace
        from unittest.mock import MagicMock
        with tempfile.TemporaryDirectory() as temporary:
            repo = Path(temporary)
            (repo / '.dev').mkdir()
            evidence = repo / 'evidence'
            evidence.mkdir()
            args = SimpleNamespace(config=repo / 'policy.yml', port=18206, diagnostic=False)
            unit = MagicMock()
            unit.start.return_value = {}
            def stop():
                state = next((repo / '.dev').iterdir())
                (state / 'daemon.log').write_text('failure evidence')
                return {'process_terminated': True}
            unit.close.side_effect = stop
            with patch.object(CAP, 'REPO', repo), patch.object(CAP, 'PrivateUnit', return_value=unit), \
                    patch.object(CAP, 'private_config', return_value=policy()), \
                    patch.object(CAP.BASE, 'digest', return_value='sha'), \
                    patch.object(CAP, 'ready', side_effect=RuntimeError('readiness failure')), \
                    patch.object(CAP.shutil, 'copy2', side_effect=OSError('disk full')):
                value = CAP.sample(args, repo / 'jar', {'samples': []}, 0, evidence)
            self.assertTrue(value['temporary_state_removed'])
            self.assertEqual(value['status'], 'ERROR')
            self.assertIn('Evidence copy: disk full', value['cleanup_errors'])
            self.assertTrue((evidence / 'report.json').is_file())


if __name__ == '__main__':
    unittest.main()
