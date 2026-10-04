#!/usr/bin/env python3
"""Compare CAP-3 stage mechanisms in isolated JVMs; temporary classes are always removed."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import shutil
import statistics
import subprocess
import tempfile
import xml.etree.ElementTree as ET

REPO = Path(__file__).resolve().parents[2]
JAVA_ROOTS = {
    "core/ioc-domain": ["com/iocextractor/domain/attribute/MarkerSourceAttributor"],
    "core/ioc-application": [
        "com/iocextractor/application/pipeline/stage/ExtractIndicatorsStage",
        "com/iocextractor/application/pipeline/stage/PrepareRoutedArtifactsStage"],
    "platform/platform-etl": [
        "com/iocextractor/platform/etl/Envelope", "com/iocextractor/platform/etl/PipelineRunner"],
    "platform/platform-diagnostics": [
        "com/iocextractor/diagnostics/result/BoundedNotification",
        "com/iocextractor/diagnostics/result/DiagnosticSummary"]}
NEW_CLASSES = ["com/iocextractor/diagnostics/result/DiagnosticBatch",
               "com/iocextractor/diagnostics/result/BoundedDiagnosticCollector"]
PROFILES = ("attribution-ordered", "attribution-unordered", "extraction", "preparation")
METRICS = ("elapsed_ms", "allocated_bytes", "sampled_peak_heap_bytes",
           "sampled_current_rss_kib", "retained_heap_bytes", "stage_retained_diagnostics")
FLAGS = ["-Xms128m", "-Xmx512m"]
NS = {"m": "http://maven.apache.org/POM/4.0.0"}


def command(args, *, timeout=120):
    result = subprocess.run(args, cwd=REPO, text=True, stdout=subprocess.PIPE,
                            stderr=subprocess.STDOUT, timeout=timeout, check=False)
    if result.returncode:
        raise RuntimeError(f"Command failed ({result.returncode}): {args[0]}\n{result.stdout}")
    return result.stdout


def digest_files(root):
    digest = hashlib.sha256()
    files = sorted(path for path in root.rglob("*") if path.is_file())
    for path in files:
        digest.update(str(path.relative_to(root)).encode())
        digest.update(hashlib.sha256(path.read_bytes()).digest())
    return {"files": len(files), "sha256": digest.hexdigest()}


def freeze_common(root, re2j):
    pom = ET.parse(REPO / "pom.xml")
    directories = [REPO / item.text / "target/classes"
                   for item in pom.findall("m:modules/m:module", NS)]
    directories.append(REPO / "bootstrap/ioc-app/target/test-classes")
    paths = []
    for index, directory in enumerate(directories):
        if not directory.is_dir():
            continue
        destination = root / str(index)
        for source in directory.rglob("*.class"):
            target = destination / source.relative_to(directory)
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(source, target)
        paths.append(destination)
    jar = root / "re2j.jar"
    shutil.copy2(re2j, jar)
    paths.append(jar)
    return os.pathsep.join(map(str, paths))


def compile_overlay(root, side, baseline, classpath):
    source_root, classes = root / side / "source", root / side / "classes"
    paths = [(module, name) for module, names in JAVA_ROOTS.items() for name in names]
    if side == "after":
        paths.extend(("platform/platform-diagnostics", name) for name in NEW_CLASSES)
    sources = []
    for module, name in paths:
        relative = f"{module}/src/main/java/{name}.java"
        source = source_root / f"{name}.java"
        source.parent.mkdir(parents=True, exist_ok=True)
        source.write_text(command(["git", "show", f"{baseline}:{relative}"])
                          if side == "before" else (REPO / relative).read_text())
        sources.append(str(source))
    classes.mkdir(parents=True)
    command(["java", "com.sun.tools.javac.Main", "--release", "21",
             "-cp", classpath, "-d", str(classes), *sources])
    return str(classes) + os.pathsep + classpath


def summarize(samples):
    summary = {}
    for profile, count in sorted({(row["profile"], row["count"]) for row in samples}):
        metrics = {}
        for metric in METRICS:
            before = [row[metric] for row in samples if row["profile"] == profile
                      and row["count"] == count and row["revision"] == "before"]
            after = [row[metric] for row in samples if row["profile"] == profile
                     and row["count"] == count and row["revision"] == "after"]
            b, a = statistics.median(before), statistics.median(after)
            metrics[metric] = {"before_median": b, "after_median": a,
                               "after_over_before": a / b if b else None,
                               "before_range": [min(before), max(before)],
                               "after_range": [min(after), max(after)]}
        summary[f"{profile}-{count}"] = metrics
    return summary


def run_matrix(classpaths, workloads, pairs, timeout):
    samples = []
    for count, profiles in workloads:
        for profile in profiles:
            expected = None
            for pair in range(pairs):
                sides = ("before", "after") if pair % 2 == 0 else ("after", "before")
                for side in sides:
                    output = command(["java", *FLAGS, "-cp", classpaths[side],
                                      "com.iocextractor.bootstrap.ProcessingStageCapacity",
                                      profile, str(count)], timeout=timeout)
                    lines = [line.removeprefix("STAGE_CAPACITY ") for line in output.splitlines()
                             if line.startswith("STAGE_CAPACITY ")]
                    if len(lines) != 1:
                        raise RuntimeError(f"Missing or duplicate stage outcome: {output}")
                    row = json.loads(lines[0])
                    if (row["profile"], row["count"]) != (profile, count):
                        raise RuntimeError("Wrong workload outcome")
                    if expected is not None and row["signature"] != expected:
                        raise RuntimeError("Before/after semantic signatures differ")
                    expected = row["signature"]
                    row.update(revision=side, pair=pair)
                    samples.append(row)
                    print(f"{profile} {count} pair={pair + 1} {side}: "
                          f"{row['elapsed_ms']:.3f} ms", flush=True)
    return samples


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--baseline", required=True, help="Git revision before CAP-3")
    parser.add_argument("--output", type=Path, required=True, help="New compact JSON evidence file")
    parser.add_argument("--profiles", nargs="+", choices=PROFILES, default=list(PROFILES))
    parser.add_argument("--counts", nargs="+", type=int, default=[100000])
    parser.add_argument("--large-attribution-count", type=int,
                        help="Additional attribution-only size, without high-volume diagnostic baseline")
    parser.add_argument("--pairs", type=int, default=5)
    parser.add_argument("--timeout", type=int, default=120, help="Per-fork seconds")
    parser.add_argument("--re2j-jar", type=Path)
    args = parser.parse_args()
    counts = args.counts + ([args.large_attribution_count] if args.large_attribution_count else [])
    if args.pairs < 1 or args.timeout < 1 or any(count < 250 or count % 250 for count in counts):
        parser.error("Positive pairs/timeout and counts divisible by 250 are required")
    if args.output.exists():
        parser.error("Evidence output already exists")
    status = command(["git", "status", "--porcelain"])
    if status.strip():
        parser.error("Commit source changes before recording primary evidence")
    baseline = command(["git", "rev-parse", "--verify", f"{args.baseline}^{{commit}}"]).strip()
    candidate = command(["git", "rev-parse", "HEAD"]).strip()
    if not (REPO / "bootstrap/ioc-app/target/test-classes/com/iocextractor/bootstrap/ProcessingRouteComparison.class").is_file():
        parser.error("Compile the bootstrap test probe first (see tools/dev/README.md)")
    pom = ET.parse(REPO / "pom.xml")
    version = pom.find("m:properties/m:re2j.version", NS).text
    re2j = args.re2j_jar or Path.home() / f".m2/repository/com/google/re2j/re2j/{version}/re2j-{version}.jar"
    if not re2j.is_file():
        parser.error("RE2/J jar is unavailable; specify --re2j-jar for a custom Maven repository")
    scratch = REPO / ".dev"
    scratch.mkdir(exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="cap3-stages-", dir=scratch) as temporary:
        root = Path(temporary)
        common = freeze_common(root / "common", re2j)
        classpaths = {side: compile_overlay(root, side, baseline, common) for side in ("before", "after")}
        probe = root / "probe"
        probe.mkdir()
        command(["java", "com.sun.tools.javac.Main", "--release", "21",
                 "-cp", classpaths["after"], "-d", str(probe),
                 str(REPO / "bootstrap/ioc-app/src/test/java/com/iocextractor/bootstrap/ProcessingStageCapacity.java")])
        identities = {name: digest_files(root / name) for name in ("before", "after", "common", "probe")}
        workloads = [(count, args.profiles) for count in args.counts]
        if args.large_attribution_count:
            workloads.append((args.large_attribution_count,
                              [profile for profile in args.profiles if profile.startswith("attribution")]))
        samples = run_matrix({side: str(probe) + os.pathsep + path for side, path in classpaths.items()},
                             workloads, args.pairs, args.timeout)
        if identities != {name: digest_files(root / name) for name in identities}:
            raise RuntimeError("Frozen runtime changed during measurement")
        if candidate != command(["git", "rev-parse", "HEAD"]).strip() or command(["git", "status", "--porcelain"]).strip():
            raise RuntimeError("Source identity changed during measurement")
        report = {"baseline_commit": baseline, "candidate_commit": candidate,
                  "source_dirty": False, "runtimes": identities, "jvm_flags": FLAGS,
                  "driver_sha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
                  "java": command(["java", "-version"]).splitlines()[0], "host": platform.platform(),
                  "cgroup": {name: Path(f"/sys/fs/cgroup/{name}").read_text().strip()
                             for name in ("cpu.max", "memory.max") if Path(f"/sys/fs/cgroup/{name}").exists()},
                  "scope": "Focused stage mechanisms, one full warmup per fork; no agent, Spring, storage or SMB",
                  "memory_scope": "10ms sampled phase heap/current RSS; retained heap includes fixture and final outcome",
                  "attribution_fixture": "250 occurrences per section at its inclusive position; unordered seed 302031",
                  "diagnostic_fixture": "Repeated decision carrier; actual diagnostic constructed per item; limit 128; preparation ends with ERROR",
                  "pairs": args.pairs, "samples": samples, "summary": summarize(samples),
                  "semantic_equivalence": True, "acceptance": "G3 mechanism evidence; whole-service resource acceptance remains CAP-6",
                  "temporary_state": "No databases; source/class snapshots removed on success and failure"}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2) + "\n")
    print(f"Evidence: {args.output}", flush=True)


if __name__ == "__main__":
    main()
