#!/usr/bin/env python3
"""Offline contracts for paired CAP-3 evidence and temporary-state ownership."""

import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.dont_write_bytecode = True
SPEC = importlib.util.spec_from_file_location(
    "stage_capacity", Path(__file__).resolve().parents[1] / "dev/processing-stage-capacity.py")
CAPACITY = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CAPACITY)


def outcome(signature="same", elapsed=10):
    return {"profile": "extraction", "count": 1000, "signature": signature,
            **{metric: elapsed if metric == "elapsed_ms" else 100 for metric in CAPACITY.METRICS}}


class CapacityContracts(unittest.TestCase):
    def test_pairs_alternate_execution_without_losing_pair_identity(self):
        output = "STAGE_CAPACITY " + json.dumps(outcome())
        with patch.object(CAPACITY, "command", return_value=output):
            rows = CAPACITY.run_matrix({"before": "b", "after": "a"},
                                       [(1000, ["extraction"])], 3, 2)
        self.assertEqual([(row["pair"], row["revision"]) for row in rows],
                         [(0, "before"), (0, "after"), (1, "after"),
                          (1, "before"), (2, "before"), (2, "after")])

    def test_semantic_mismatch_rejects_evidence(self):
        outputs = ["STAGE_CAPACITY " + json.dumps(outcome(signature)) for signature in ("a", "b")]
        with patch.object(CAPACITY, "command", side_effect=outputs):
            with self.assertRaisesRegex(RuntimeError, "semantic signatures differ"):
                CAPACITY.run_matrix({"before": "b", "after": "a"},
                                    [(1000, ["extraction"])], 1, 2)

    def test_missing_or_duplicate_samples_reject_evidence(self):
        line = "STAGE_CAPACITY " + json.dumps(outcome())
        for output in ("", line + "\n" + line):
            with self.subTest(output=output), patch.object(CAPACITY, "command", return_value=output):
                with self.assertRaisesRegex(RuntimeError, "Missing or duplicate"):
                    CAPACITY.run_matrix({"before": "b", "after": "a"},
                                        [(1000, ["extraction"])], 1, 2)

    def test_summary_groups_cardinality_and_keeps_zero_baseline_ratio_null(self):
        rows = []
        for count, before, after in ((1000, 10, 5), (100000, 30, 12)):
            for side, elapsed in (("before", before), ("after", after)):
                row = outcome(elapsed=elapsed)
                row.update(count=count, revision=side, pair=0, stage_retained_diagnostics=0)
                rows.append(row)
        report = CAPACITY.summarize(rows)
        self.assertEqual(report["extraction-1000"]["elapsed_ms"]["after_over_before"], .5)
        self.assertEqual(report["extraction-100000"]["elapsed_ms"]["after_over_before"], .4)
        self.assertIsNone(report["extraction-1000"]["stage_retained_diagnostics"]["after_over_before"])

    def test_failed_fork_removes_private_classes_and_does_not_publish_partial_report(self):
        with tempfile.TemporaryDirectory() as temporary:
            repo = Path(temporary)
            sampler = repo / "bootstrap/ioc-app/target/test-classes/com/iocextractor/bootstrap/ProcessingRouteComparison.class"
            sampler.parent.mkdir(parents=True)
            sampler.touch()
            (repo / "pom.xml").write_text(
                '<project xmlns="http://maven.apache.org/POM/4.0.0">'
                '<properties><re2j.version>1.8</re2j.version></properties></project>')
            jar, report = repo / "re2j.jar", repo / "report.json"
            jar.touch()

            def freeze(root, _jar):
                root.mkdir()
                (root / "shared.class").write_bytes(b"shared")
                return str(root)

            def overlay(root, side, *_args):
                (root / side).mkdir()
                (root / side / "private.class").write_bytes(b"private")
                return str(root / side)

            def commands(args, **_kwargs):
                return "" if args[:3] == ["git", "status", "--porcelain"] else "identity"

            arguments = ["probe", "--baseline", "baseline", "--output", str(report),
                         "--re2j-jar", str(jar), "--pairs", "1"]
            with patch.object(CAPACITY, "REPO", repo), patch.object(sys, "argv", arguments), \
                    patch.object(CAPACITY, "command", side_effect=commands), \
                    patch.object(CAPACITY, "freeze_common", side_effect=freeze), \
                    patch.object(CAPACITY, "compile_overlay", side_effect=overlay), \
                    patch.object(CAPACITY, "run_matrix", side_effect=RuntimeError("failed fork")):
                with self.assertRaisesRegex(RuntimeError, "failed fork"):
                    CAPACITY.main()
            self.assertEqual(list((repo / ".dev").iterdir()), [])
            self.assertFalse(report.exists())


if __name__ == "__main__":
    unittest.main()
