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
