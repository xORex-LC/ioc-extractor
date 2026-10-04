#!/usr/bin/env python3
"""Alternate frozen reference/candidate JVMs with identical selected routing semantics."""

import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import statistics
import sys

sys.dont_write_bytecode = True
SPEC = importlib.util.spec_from_file_location(
    "route_comparison", Path(__file__).with_name("processing-route-comparison.py"))
COMPARISON = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(COMPARISON)

PROFILES = {
    "repeat": ("domains", 8000, 20, 2000, 20),
    "unique": ("domains", 8000, 8000, 2000, 2000),
    "collapse": ("host-collapse", 100000, 25000, 10000, 2500),
    "capacity-10k": ("mixed", 10000, 10000, 10000, 10000),
    "capacity-100k": ("mixed", 100000, 100000, 100000, 100000),
}
METRICS = ("elapsed_ms", "allocated_main_bytes", "sampled_peak_heap_bytes",
           "sampled_peak_current_rss_kib", "sampled_peak_rss_kib", "startup_ms",
           "gc_count", "gc_time_ms")


def frozen_runtime(root):
    report = json.loads((root / "report.json").read_text())
    paths = [root / "probe-classes", root / "test-resources", root / "app-classes"]
    jars = sorted((root / "lib").glob("*.jar"))
    if not jars or any(not path.is_dir() for path in paths):
        raise ValueError(f"Incomplete frozen runtime: {root}")
    files = sorted(path for directory in paths + [root / "lib"]
                   for path in directory.rglob("*") if path.is_file())
    digests = {str(path.relative_to(root)): hashlib.sha256(path.read_bytes()).hexdigest()
               for path in files}
    return ":".join(map(str, paths + jars)), {
        "snapshot": str(root), "source_commit": report["commit"],
        "source_dirty": report["dirty"], "source_diff_sha256": report["worktree_diff_sha256"],
        "runtime_files": digests,
    }


def compare(reference, candidate):
    """Within selected-path optimizations even diagnostic differences are regressions."""
    if reference != candidate:
        raise RuntimeError("Selected reference/candidate signatures or outcomes differ")


def summarize(samples):
    result = {}
    for kind in sorted({row["kind"] for row in samples}):
        result[kind] = {}
        for metric in METRICS:
            before = [float(row[metric]) for row in samples
                      if row["kind"] == kind and row["revision"] == "before"]
            after = [float(row[metric]) for row in samples
                     if row["kind"] == kind and row["revision"] == "after"]
            b, a = statistics.median(before), statistics.median(after)
            result[kind][metric] = {
                "before_median": b, "after_median": a,
                "after_over_before": a / b if b else None,
                "paired_ratios": [y / x if x else None for x, y in zip(before, after)],
                "before_range": [min(before), max(before)],
                "after_range": [min(after), max(after)],
            }
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--reference", type=Path, required=True)
    parser.add_argument("--candidate", type=Path, required=True)
    parser.add_argument("--workspace", type=Path, required=True)
    parser.add_argument("--profile", choices=["all", *PROFILES], default="all")
    parser.add_argument("--workload", choices=["both", "document", "import"], default="both")
    parser.add_argument("--pairs", type=int, default=5)
    parser.add_argument("--diagnostics", action="store_true")
    parser.add_argument("--retain-state", action="store_true",
                        help="Keep generated databases/CSVs; default retains measured evidence only")
    parser.add_argument("--agent", type=Path,
                        help="Common diagnostic agent jar; required with --diagnostics")
    args = parser.parse_args()
    root = args.workspace.resolve()
    if args.pairs < 1 or not root.is_relative_to(COMPARISON.REPO / ".dev"):
        parser.error("Positive pairs and a repo-local .dev workspace are required")
    if args.diagnostics != (args.agent is not None):
        parser.error("Diagnostic forks require --agent; primary forks must not attach it")
    root.mkdir(parents=True, exist_ok=True)
    if list(root.iterdir()):
        parser.error("Use an empty workspace")
    runtimes = {"before": args.reference.resolve(), "after": args.candidate.resolve()}
    frozen = {side: frozen_runtime(path) for side, path in runtimes.items()}
    # Compare both directions: an extra probe/resource also changes the experiment.
    for directory in ("test-resources", "probe-classes"):
        snapshots = [{str(path.relative_to(runtime / directory)): hashlib.sha256(path.read_bytes()).hexdigest()
                      for path in (runtime / directory).rglob("*") if path.is_file()}
                     for runtime in runtimes.values()]
        if snapshots[0] != snapshots[1]:
            raise RuntimeError(f"Frozen {directory} differ; use identical measurement probes and policies")
    os.environ.update(DEBUG="false", TRACE="false", LOGGING_LEVEL_ROOT="WARN")
    report = {"runtimes": {side: value[1] for side, value in frozen.items()},
              "pairs": args.pairs, "profiles": {}, "execution_order": [],
              "mode": "diagnostic" if args.diagnostics else "primary",
              "logging": {key: os.environ[key] for key in ("DEBUG", "TRACE", "LOGGING_LEVEL_ROOT")},
              "jvm_flags": ["-Xms128m", "-Xmx512m"],
              "java": COMPARISON.command(["java", "-version"]).splitlines()[0],
              "host": COMPARISON.platform.platform(),
              "cgroup": {name: Path(f"/sys/fs/cgroup/{name}").read_text().strip()
                         for name in ("cpu.max", "memory.max") if Path(f"/sys/fs/cgroup/{name}").exists()},
              "driver_sha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
              "comparison_sha256": hashlib.sha256(Path(COMPARISON.__file__).read_bytes()).hexdigest(),
              "acceptance": "local optimization evidence; customer resource budget remains open"}
    if args.agent:
        report["agent_sha256"] = hashlib.sha256(args.agent.read_bytes()).hexdigest()
    selected = PROFILES if args.profile == "all" else {args.profile: PROFILES[args.profile]}
    try:
        for name, (shape, document_rows, document_unique, import_rows, import_unique) in selected.items():
            profile = root / name
            profile.mkdir()
            fixtures = COMPARISON.fixtures(profile, document_rows, import_rows,
                                           document_unique, import_unique, shape)
            samples = []
            item = {"samples": samples, "shape": shape,
                    "warmups": 0 if name.startswith("capacity-") else 1,
                    "input_sha256": {kind: hashlib.sha256(path.read_bytes()).hexdigest()
                                     for kind, path in fixtures.items()}}
            report["profiles"][name] = item
            for side in runtimes:
                (profile / side).mkdir()
                if args.agent:
                    (profile / side / "comparison-diagnostics.jar").write_bytes(args.agent.read_bytes())
            for kind, fixture in fixtures.items():
                if args.workload != "both" and args.workload != kind:
                    continue
                for iteration in range(args.pairs):
                    signatures, configs = {}, {}
                    for side in (["before", "after"] if iteration % 2 == 0 else ["after", "before"]):
                        print(f"{name} {kind} pair={iteration + 1} {side}", flush=True)
                        report["execution_order"].append([name, kind, iteration, side])
                        metrics, signature = COMPARISON.run_one(
                            profile / side, fixture, kind, True, iteration, frozen[side][0],
                            document_rows if kind == "document" else import_rows,
                            document_unique if kind == "document" else import_unique,
                            0 if name.startswith("capacity-") else 1,
                            runtimes[side] / "test-resources", args.diagnostics, shape,
                            capacity=name.startswith("capacity-"), retain_state=args.retain_state)
                        metrics["revision"] = side
                        samples.append(metrics)
                        signatures[side] = signature
                        configs[side] = metrics["config_sha256"]
                        (root / "partial.json").write_text(json.dumps(report, indent=2) + "\n")
                    compare(signatures["before"], signatures["after"])
                    compare(configs["before"], configs["after"])
            item["summary"] = summarize(samples)
            item["equivalent_results"] = True
        for side, runtime in runtimes.items():
            compare(frozen[side][1], frozen_runtime(runtime)[1])
    except Exception as failure:
        report["failure"] = str(failure)
        (root / "failure.json").write_text(json.dumps(report, indent=2) + "\n")
        raise
    (root / "report.json").write_text(json.dumps(report, indent=2) + "\n")
    print(f"PASS: all selected results and diagnostics match; {root}/report.json")


if __name__ == "__main__":
    main()
