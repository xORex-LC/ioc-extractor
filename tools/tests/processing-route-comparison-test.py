#!/usr/bin/env python3
"""Offline contracts for comparison fixture cardinality and paired statistics."""

import importlib.util
import sys

sys.dont_write_bytecode = True
from pathlib import Path
import tempfile
import unittest


SPEC = importlib.util.spec_from_file_location(
    "route_comparison", Path(__file__).resolve().parents[1] / "dev/processing-route-comparison.py")
COMPARISON = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(COMPARISON)


class ComparisonTest(unittest.TestCase):
    def test_missing_or_unreached_instrumentation_invalidates_diagnostic_fork(self):
        for output in ("", "ROUTE_DIAGNOSTICS preparation_calls=1\n",
                       "ROUTE_DIAGNOSTICS preparation_calls=1 preparation_nanos=0\n"):
            with self.assertRaises(RuntimeError):
                COMPARISON.diagnostic_counters(output)
        result = COMPARISON.diagnostic_counters(
            "ROUTE_DIAGNOSTICS preparation_calls=2 preparation_nanos=100 classifications=4 "
            "derived_classifications=2\n")
        self.assertEqual(result["original_classifications"], 2)
        self.assertEqual(result["argument_splits"], 0)
        self.assertEqual(result["string_template_sends"], 0)

    def test_fixture_cardinality_is_explicit_for_each_input(self):
        with tempfile.TemporaryDirectory() as root:
            paths = COMPARISON.fixtures(Path(root), 8, 6, 4, 6)
            csv = paths["import"].read_text().splitlines()[1:]
            self.assertEqual(len(csv), 6)
            self.assertEqual(len(set(csv)), 6)
            document = paths["document"].read_text()
            self.assertEqual(document.count("<p>"), 8)
            for number in range(4):
                self.assertEqual(document.count(f"benchmark-{number}.example.com"), 2)

    def test_mixed_long_and_warmup_identities_are_disjoint(self):
        for shape in ("domains", "mixed", "long"):
            for kind in ("document", "import"):
                measured = COMPARISON.fixture_values(20, shape, kind)
                warmup = COMPARISON.fixture_values(20, shape, kind, 1)
                self.assertEqual(len(set(measured)), 20)
                self.assertTrue(set(measured).isdisjoint(warmup))
        mixed = COMPARISON.fixture_values(4, "mixed")
        self.assertEqual(mixed[0], "benchmark-0.example.com")
        self.assertEqual(mixed[1], "198.18.0.1")
        self.assertTrue(mixed[2].startswith("https://benchmark-2.example.com/"))
        self.assertEqual(mixed[3], "00000000000000000000000000000004")
        self.assertGreater(len(COMPARISON.fixture_values(1, "long")[0]), 4096)

    def test_shape_cardinality_and_original_view_configuration_are_explicit(self):
        counts = COMPARISON.expected_document_counts(8, 4, "mixed")
        self.assertEqual(counts, dict(masks=2, ip_list=1, address_blacklist=3,
                                     hashes=1, ioc_aggregate=4))
        with tempfile.TemporaryDirectory() as root:
            COMPARISON.config("document", True, Path(root), shape="mixed")
            config = (Path(root) / "configs/application.yml").read_text()
            self.assertNotIn("default-view: host", config)
            self.assertIn("default-view: original", config)
            self.assertIn("operation: network.host", config)

    def test_url_overlap_diagnostics_are_preserved_in_both_paths(self):
        self.assertEqual(COMPARISON.expected_document_diagnostics(10, 4, "mixed", True), 2)
        self.assertEqual(COMPARISON.expected_document_diagnostics(10, 4, "mixed", False), 8)
        self.assertEqual(COMPARISON.expected_document_diagnostics(10, 4, "long", True), 10)
        self.assertEqual(COMPARISON.expected_document_diagnostics(10, 4, "long", False), 16)
        self.assertEqual(COMPARISON.expected_document_diagnostics(10, 4, "domains", False), 6)

    def test_statistics_support_a_single_workload_without_fabricating_the_other(self):
        rows = [dict(kind="document", path=path, iteration=0, elapsed_ms=value,
                     throughput_per_s=value, allocated_main_bytes=value,
                     sampled_peak_heap_bytes=value, sampled_peak_rss_kib=value,
                     sampled_peak_current_rss_kib=value, startup_ms=value,
                     gc_count=0, gc_time_ms=0)
                for path, value in (("compatible", 1), ("selected", 2))]
        self.assertEqual(set(COMPARISON.summary(rows)), {"document"})

    def test_statistics_preserve_pairs_and_zero_gc_is_not_a_ratio(self):
        rows = []
        for kind in ("document", "import"):
            for iteration in range(2):
                for path, value in (("compatible", iteration + 1), ("selected", 2 * (iteration + 1))):
                    rows.append(dict(kind=kind, path=path, iteration=iteration,
                                     elapsed_ms=value, throughput_per_s=value,
                                     allocated_main_bytes=value, sampled_peak_heap_bytes=value,
                                     sampled_peak_rss_kib=value, sampled_peak_current_rss_kib=value,
                                     startup_ms=value, gc_count=0, gc_time_ms=0))
        result = COMPARISON.summary(rows)
        self.assertEqual(result["document"]["elapsed_ms"]["paired_ratios"], [2, 2])
        self.assertEqual(result["document"]["elapsed_ms"]["selected_over_compatible"], 2)
        self.assertEqual(result["import"]["gc_count"]["paired_ratios"], [])
        self.assertIsNone(result["import"]["gc_time_ms"]["selected_over_compatible"])


if __name__ == "__main__":
    unittest.main()
