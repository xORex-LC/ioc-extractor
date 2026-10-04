#!/usr/bin/env python3
"""Offline contracts for comparison fixture cardinality and paired statistics."""

import importlib.util
import sys

sys.dont_write_bytecode = True
from pathlib import Path
import tempfile
import unittest
import sqlite3
from unittest.mock import patch


SPEC = importlib.util.spec_from_file_location(
    "route_comparison", Path(__file__).resolve().parents[1] / "dev/processing-route-comparison.py")
COMPARISON = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(COMPARISON)
OPTIMIZATION_SPEC = importlib.util.spec_from_file_location(
    "optimization_comparison", Path(__file__).resolve().parents[1] / "dev/processing-optimization-comparison.py")
OPTIMIZATION = importlib.util.module_from_spec(OPTIMIZATION_SPEC)
OPTIMIZATION_SPEC.loader.exec_module(OPTIMIZATION)


class ComparisonTest(unittest.TestCase):
    def test_capacity_document_profile_enables_canonical_lifecycle(self):
        with tempfile.TemporaryDirectory() as root:
            COMPARISON.config("document", True, Path(root), shape="mixed", capacity=True)
            contents = (Path(root) / "configs/application.yml").read_text()
            self.assertIn("lifecycle:\n    validity:\n      mode: fixed", contents)
            self.assertIn("document-plan: customer-hosts", contents)

    def test_capacity_oracle_checks_all_public_fields_and_missing_rows(self):
        with sqlite3.connect(":memory:") as connection:
            columns = {
                "masks": "mask,url_match,host_match,score,time_last_seen,time_first_seen,threat_type,source,description",
                "ip_list": "ip,score,time_last_seen,time_first_seen,threat_type,source,description",
                "address_blacklist": "forbidden_url,forbidden_ip",
                "hashes": "hash_md5,hash_sha256,hash_sha1,score,time_last_seen,time_first_seen,threat_type,source,description",
                "ioc_aggregate": "name,ip_address,url_match,host_match,hash"}
            domain = "benchmark-0.example.com"
            records = {
                "masks": ([domain], (domain, "u:hEX", "h:dEX", None, None, None, None, "БИБ-0001", None)),
                "address_blacklist": ([domain, None], (domain, None)),
                "ioc_aggregate": ([None, None, domain, None], ("БИБ-0001", None, None, domain, None))}
            for artifact, header in columns.items():
                connection.execute(f"CREATE TABLE {artifact} (row_key TEXT, {header.replace(',', ' TEXT,')} TEXT)")
                if artifact in records:
                    key, values = records[artifact]
                    connection.execute(f"INSERT INTO {artifact} VALUES ({','.join('?' for _ in range(len(values)+1))})",
                                       (COMPARISON.canonical_digest(key), *values))
            result = COMPARISON.capacity_document_fields(connection, 1, 1, 0, "domains")
            self.assertEqual(set(result), set(columns))
            connection.execute("UPDATE masks SET score='20'")
            with self.assertRaisesRegex(RuntimeError, "wrong fields or key in masks"):
                COMPARISON.capacity_document_fields(connection, 1, 1, 0, "domains")
            connection.execute("UPDATE masks SET score=NULL")
            connection.execute("DELETE FROM address_blacklist")
            with self.assertRaisesRegex(RuntimeError, "missing rows in address_blacklist"):
                COMPARISON.capacity_document_fields(connection, 1, 1, 0, "domains")

    def test_retired_engine_cannot_be_configured(self):
        with tempfile.TemporaryDirectory() as root:
            for kind in ("document", "import"):
                with self.assertRaisesRegex(ValueError, "compatible processing engine is retired"):
                    COMPARISON.config(kind, False, Path(root))

    def test_selected_optimization_comparison_rejects_diagnostic_changes(self):
        baseline = {"fields": ["same"], "outcome": "DEBUG overlap"}
        OPTIMIZATION.compare(baseline, dict(baseline))
        with self.assertRaisesRegex(RuntimeError, "signatures or outcomes differ"):
            OPTIMIZATION.compare(baseline, baseline | {"outcome": "WARN unexpected"})

    def test_optimization_statistics_preserve_alternating_pairs(self):
        rows = []
        for iteration in range(2):
            for side in (["before", "after"] if iteration == 0 else ["after", "before"]):
                value = (iteration + 1) * (2 if side == "before" else 1)
                rows.append({"kind": "document", "revision": side,
                             **{metric: value for metric in OPTIMIZATION.METRICS}})
        metric = OPTIMIZATION.summarize(rows)["document"]["elapsed_ms"]
        self.assertEqual(metric["paired_ratios"], [0.5, 0.5])
        self.assertEqual(metric["before_median"], 3)
        self.assertEqual(metric["after_median"], 1.5)

    def test_host_collapse_has_distinct_urls_shared_hosts_repeats_and_sources(self):
        values = COMPARISON.fixture_values(8, "host-collapse", collapse_hosts=4)
        self.assertEqual(len(set(values)), 8)
        self.assertEqual({COMPARISON.urlsplit(value).hostname for value in values},
                         {"benchmark-0.example.com", "benchmark-2.example.com", "198.18.0.1", "198.18.0.3"})
        self.assertEqual(values[0], "https://benchmark-0.example.com:8000/endpoint/0?variant=0&download=true#section")
        self.assertTrue(values[4].startswith("http://benchmark-0.example.com:8004/endpoint/4?"))
        self.assertEqual(COMPARISON.expected_document_diagnostics(80, 40, "host-collapse", True), 80)
        warmed = COMPARISON.fixture_values(8, "host-collapse", offset=1, collapse_hosts=4)
        self.assertTrue({COMPARISON.urlsplit(v).hostname for v in values}.isdisjoint(
            {COMPARISON.urlsplit(v).hostname for v in warmed}))
        with tempfile.TemporaryDirectory() as root:
            paths = COMPARISON.fixtures(Path(root), 32, 16, 8, 8, "host-collapse", collapse_hosts=4)
            sections = paths["document"].read_text().split("<h2>БИБ-0002</h2>")
            self.assertEqual(len(sections), 2)
            for value in values:
                self.assertEqual(sections[0].count(f"<p>{value}</p>"), 2)
                self.assertEqual(sections[1].count(f"<p>{value}</p>"), 2)
            rows = paths["import"].read_text().splitlines()[1:]
            self.assertEqual(len(rows), 16)
            self.assertEqual(rows[0], rows[1])
            self.assertTrue(any(row.endswith(";Feed Alpha") for row in rows))
            self.assertTrue(any(row.endswith(";Feed Beta") for row in rows))

    def test_host_collapse_keeps_host_routes_and_complete_ipv4_mask_policy(self):
        for kind in ("document", "import"):
            with tempfile.TemporaryDirectory() as root:
                COMPARISON.config(kind, True, Path(root), shape="host-collapse")
                contents = (Path(root) / "configs/application.yml").read_text()
                self.assertNotIn("default-view: original", contents)
                self.assertIn("default-view: host", contents)
                self.assertIn("exclude: []", contents)
                self.assertIn("selection-column: name", contents)
                self.assertIn("operation: network.host", contents)
                with self.assertRaises(ValueError):
                    COMPARISON.config(kind, False, Path(root), shape="host-collapse")

    def test_cleanup_oracle_rejects_wrong_final_key_source_and_provenance(self):
        with sqlite3.connect(":memory:") as connection:
            connection.executescript("""
                CREATE TABLE masks(id INTEGER, mask TEXT, url_match TEXT, host_match TEXT, source TEXT, row_key TEXT);
                CREATE TABLE masks_sources(row_id INTEGER, source_key TEXT, occurrences INTEGER);
                INSERT INTO masks VALUES(1, 'benchmark-0.example.com', 'u:hEX', 'h:dEX', 'Feed Alpha',
                    '7d5637c40422caa36f70688376b88739910b20a68c5c0a4773b6461c7b03ae74');
                INSERT INTO masks VALUES(2, '198.18.0.1', 'u:hAS', 'h:dAS', 'Feed Beta', 'invalid');
                INSERT INTO masks_sources VALUES(1, 'dataframe-import:local-hosts', 1),
                    (2, 'dataframe-import:local-hosts', 1);
                """)
            # Independent wire literals establish expected SHA-256 inputs.
            import hashlib
            connection.execute("UPDATE masks SET row_key = ? WHERE id=1",
                               (hashlib.sha256(b'["benchmark-0.example.com"]').hexdigest(),))
            with self.assertRaisesRegex(RuntimeError, "final fields, keys or winner"):
                COMPARISON.host_collapse_fields(connection, "import", 2, 0)
            connection.execute("UPDATE masks SET row_key = ? WHERE id=2",
                               (hashlib.sha256(b'["198.18.0.1"]').hexdigest(),))
            self.assertEqual(len(COMPARISON.host_collapse_fields(connection, "import", 2, 0)["masks"]), 2)
            connection.execute("UPDATE masks SET source='Feed Alpha' WHERE id=2")
            with self.assertRaisesRegex(RuntimeError, "final fields, keys or winner"):
                COMPARISON.host_collapse_fields(connection, "import", 2, 0)
            connection.execute("UPDATE masks SET source='Feed Beta' WHERE id=2")
            connection.execute("UPDATE masks_sources SET occurrences=2 WHERE row_id=2")
            with self.assertRaisesRegex(RuntimeError, "provenance accounting"):
                COMPARISON.host_collapse_fields(connection, "import", 2, 0)

    def test_selected_revision_statistics_never_fabricate_compatible_ratio(self):
        row = dict(kind="document", path="selected", iteration=0, elapsed_ms=3,
                   throughput_per_s=3, allocated_main_bytes=3, sampled_peak_heap_bytes=3,
                   sampled_peak_rss_kib=3, sampled_peak_current_rss_kib=3, startup_ms=3,
                   gc_count=0, gc_time_ms=0)
        result = COMPARISON.summary([row])["document"]["elapsed_ms"]
        self.assertEqual(result["selected_median"], 3)
        self.assertIsNone(result["compatible_median"])
        self.assertIsNone(result["selected_over_compatible"])

    def test_import_signature_includes_internal_ids_and_occurrence_accounting(self):
        with sqlite3.connect(":memory:") as connection:
            connection.executescript("""
                CREATE TABLE masks(id INTEGER, row_key TEXT);
                CREATE TABLE masks_sources(row_id INTEGER, source_key TEXT, occurrences INTEGER);
                INSERT INTO masks VALUES(8, 'second'), (3, 'first');
                INSERT INTO masks_sources VALUES(8, 'feed', 1), (3, 'feed', 4);
                """)
            self.assertEqual(COMPARISON.canonical_import_accounting(connection), {
                "canonical_ids": [(3, "first"), (8, "second")],
                "provenance": [(3, "feed", 4), (8, "feed", 1)]})

    def test_timeout_retains_partial_output_and_never_returns_success(self):
        with tempfile.TemporaryDirectory() as root:
            output = Path(root) / "run.log"
            failure = COMPARISON.subprocess.TimeoutExpired(["java"], 2, output=b"partial sample\n")
            with patch.object(COMPARISON.subprocess, "run", side_effect=failure):
                with self.assertRaises(COMPARISON.subprocess.TimeoutExpired):
                    COMPARISON.command(["java"], output=output, timeout=2)
            self.assertEqual(output.read_text(), "partial sample\n")

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
