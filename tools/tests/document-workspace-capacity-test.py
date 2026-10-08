#!/usr/bin/env python3
"""Offline contracts for CAP-4 probe evidence and cleanup after a failed JVM."""
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.dont_write_bytecode = True
SPEC = importlib.util.spec_from_file_location("workspace_capacity",
        Path(__file__).resolve().parents[1] / "dev/document-workspace-capacity.py")
CAPACITY = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CAPACITY)


class WorkspaceCapacityContracts(unittest.TestCase):
    def test_upstream_formats_have_distinct_samples_and_always_remove_owned_files(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "source.json"
            owned = []

            def freeze(root):
                root.mkdir()
                owned.append(root.parent)
                return "classpath", "runtime-hash"

            def measure(classpath, size, state, timeout, upstream=False, source_format='html'):
                self.assertTrue(upstream)
                (state / "source-text.utf16").write_bytes(b"private")
                owned.append(state)
                return {"format": source_format, "input_occurrences": size}

            def command(arguments, **kwargs):
                return "" if arguments == ["git", "status", "--porcelain"] else "head"

            with patch.object(CAPACITY, "freeze", side_effect=freeze), \
                 patch.object(CAPACITY, "measure", side_effect=measure), \
                 patch.object(CAPACITY, "command", side_effect=command), \
                 patch.object(sys, "argv", ["probe", "--output", str(output), "--sizes",
                         "--upstream-sizes", "10", "--source-formats", "html", "docx"]):
                CAPACITY.main()
            report = json.loads(output.read_text())
            self.assertEqual(report["samples"], [])
            self.assertEqual([value["format"] for value in report["upstream_samples"]], ['html', 'docx'])
            self.assertEqual(report['status'], 'PASSED')
            self.assertTrue(report['temporary_runtime_and_state_removed'])
            self.assertTrue(all(not path.exists() for path in owned))

    def test_source_format_and_frozen_heap_flags_reach_the_jvm_probe(self):
        with patch.object(CAPACITY, "command", return_value='CAPACITY_JSON={"oracle":"PASS"}') as execute:
            result = CAPACITY.measure('cp', 1000, Path('state'), 5, upstream=True, source_format='docx')
        self.assertEqual(result['oracle'], 'PASS')
        arguments = execute.call_args.args[0]
        self.assertIn('-Xms128m', arguments)
        self.assertIn('-Xmx512m', arguments)
        self.assertIn('-XX:ActiveProcessorCount=2', arguments)
        self.assertEqual(arguments[-1], 'docx')

    def test_missing_or_duplicate_sample_cannot_be_published(self):
        line = 'CAPACITY_JSON={"rows_per_artifact":10}'
        for output in ("", line + "\n" + line):
            with self.subTest(output=output), patch.object(CAPACITY, "command", return_value=output):
                with self.assertRaisesRegex(RuntimeError, "exactly one complete sample"):
                    CAPACITY.measure("classpath", 10, Path("unused"), 5)

    def test_oracle_failure_and_timeout_remove_private_databases_and_runtime(self):
        for scope in ("reducer", "upstream"):
            for failure in (RuntimeError("oracle failed"), subprocess.TimeoutExpired("java", 5)):
                with self.subTest(scope=scope, failure=type(failure)), tempfile.TemporaryDirectory() as directory:
                    output = Path(directory) / "report.json"
                    owned = []

                    def freeze(root):
                        root.mkdir()
                        (root / "snapshot.class").write_bytes(b"compiled")
                        owned.append(root.parent)
                        return "classpath", "hash"

                    def measure(classpath, size, state, timeout, **kwargs):
                        (state / "large.db").write_bytes(b"private database")
                        owned.append(state)
                        raise failure

                    def command(arguments, **kwargs):
                        return "" if arguments == ["git", "status", "--porcelain"] else "head"

                    with patch.object(CAPACITY, "freeze", side_effect=freeze), \
                         patch.object(CAPACITY, "measure", side_effect=measure), \
                         patch.object(CAPACITY, "command", side_effect=command), \
                         patch.object(sys, "argv", ["probe", "--output", str(output),
                            *(["--sizes", "10", "--upstream-sizes"] if scope == "reducer" else
                              ["--sizes", "--upstream-sizes", "10", "--source-formats", "docx"])]):
                        with self.assertRaises(type(failure)):
                            CAPACITY.main()
                    self.assertTrue(owned)
                    self.assertTrue(all(not path.exists() for path in owned))
                    report = json.loads(output.read_text())
                    self.assertEqual(report["status"], "FAILED")
                    self.assertEqual(report["failed_cell"], {"scope": scope, "size": 10,
                            **({"format": "docx"} if scope == "upstream" else {})})
                    self.assertEqual(report["failure"]["type"], type(failure).__name__)
                    self.assertTrue(report["temporary_runtime_and_state_removed"])
                    self.assertEqual(report["samples"], [])


if __name__ == "__main__":
    unittest.main()
