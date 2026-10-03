#!/usr/bin/env python3
"""Opt-in paired IOC workload comparison using the production Spring composition."""

import argparse
import hashlib
import json
import os
import platform
import re
import shutil
import sqlite3
import statistics
import time
import subprocess
import zipfile
from pathlib import Path


REPO = Path(__file__).resolve().parents[2]
APP = REPO / "bootstrap/ioc-app"
RESOURCES = APP / "src/test/resources"
METRIC = re.compile(r"^ROUTE_COMPARISON (.+)$", re.MULTILINE)


def diagnostic_counters(output):
    records = re.findall(r"^ROUTE_DIAGNOSTICS (.+)$", output, re.MULTILINE)
    if len(records) != 1:
        raise RuntimeError("Missing or duplicate diagnostic counters")
    counters = {key: int(value) for key, value in
                (item.split("=", 1) for item in records[0].split())}
    if counters.get("preparation_calls", 0) <= 0 or counters.get("preparation_nanos", 0) <= 0:
        raise RuntimeError("Preparation instrumentation was not reached")
    counters["original_classifications"] = counters.get("classifications", 0) - counters.get(
        "derived_classifications", 0)
    for key in ("derived_classifications", "argument_splits", "string_template_sends"):
        counters.setdefault(key, 0)
    return counters


def command(args, *, cwd=REPO, output=None, timeout=600):
    try:
        result = subprocess.run(args, cwd=cwd, text=True, stdout=subprocess.PIPE,
                                stderr=subprocess.STDOUT, timeout=timeout, check=False)
    except subprocess.TimeoutExpired as failure:
        if output:
            partial = failure.stdout or ""
            output.write_text(partial.decode(errors="replace") if isinstance(partial, bytes) else partial)
        raise
    if output:
        output.write_text(result.stdout)
    if result.returncode:
        raise RuntimeError(f"Command failed ({result.returncode}); "
                           f"see {output or 'captured output'}\n{result.stdout[-2500:]}")
    return result.stdout


def source_identity():
    """Identify tracked changes and untracked sources before compiling a frozen runtime."""
    untracked = command(["git", "ls-files", "--others", "--exclude-standard"]).splitlines()
    digest = hashlib.sha256(command(["git", "diff", "HEAD"]).encode())
    for name in sorted(untracked):
        path = REPO / name
        if path.is_file():
            digest.update(name.encode())
            digest.update(path.read_bytes())
    return {"commit": command(["git", "rev-parse", "HEAD"]).strip(),
            "dirty": bool(command(["git", "status", "--porcelain"]).strip()),
            "worktree_diff_sha256": digest.hexdigest()}


def fixture_values(unique, shape="domains", kind="document", offset=0):
    """Independent, disjoint identities; shape changes never silently change routing policy."""
    values = []
    for index in range(unique):
        identity = offset * unique + index
        domain = f"benchmark-{identity}.example.com"
        variant = index % (4 if kind == "document" else 2)
        if shape == "long" or (shape == "mixed" and variant == 2):
            value = f"https://{domain}/" + "segment/" * (512 if shape == "long" else 4)
        elif shape == "mixed" and kind == "import" and variant == 1:
            value = f"https://{domain}/one"
        elif shape == "mixed" and variant == 1:
            if identity >= 131072:
                raise ValueError("Mixed IPv4 fixture exceeds the reserved benchmark address range")
            value = f"198.{18 + identity // 65536}.{identity // 256 % 256}.{identity % 256}"
        elif shape == "mixed" and variant == 3:
            value = f"{identity + 1:032x}"
        else:
            value = domain
        values.append(value)
    return values


def fixtures(workspace, document_rows, import_rows, document_unique=20, import_unique=20,
             shape="domains", offset=0):
    values = fixture_values(document_unique, shape, "document", offset)
    document = workspace / "document.html"
    document.write_text("<html><body><h2>БИБ-0001</h2>\n" + "".join(
        f"<p>{values[index % len(values)]}</p>\n" for index in range(document_rows))
        + "</body></html>\n")
    values = fixture_values(import_unique, shape, "import", offset)
    csv = workspace / "import.csv"
    csv.write_text("ioc;source\n" + "".join(
        f"{values[index % len(values)]};Feed Alpha\n" for index in range(import_rows)))
    return {"document": document, "import": csv}


def expected_document_counts(rows, unique, shape):
    count = min(rows, unique)
    if shape == "mixed":
        types = [sum(index % 4 == variant for index in range(count)) for variant in range(4)]
        return dict(masks=types[0] + types[2], ip_list=types[1],
                    address_blacklist=sum(types[:3]), hashes=types[3], ioc_aggregate=count)
    return dict(masks=count, ip_list=0, address_blacklist=count, hashes=0, ioc_aggregate=count)


def config(kind, selected, target, resources=RESOURCES, shape="domains"):
    if kind == "document":
        contents = (resources / "application-customer-routes.yml").read_text() \
            if selected else "ioc: {}\n"
    else:
        contents = (resources / "application-selected-import-production.yml").read_text()
        if not selected:
            begin = contents.index("        processed-route:\n")
            end = contents.index("    runtime:\n", begin)
            contents = contents[:begin] + contents[end:]
    if selected and shape != "domains":
        # Matched output semantics for mixed/long profiles; cleanup is qualified separately.
        contents = contents.replace("default-view: host", "default-view: original")
    path = target / "configs/application.yml"
    path.parent.mkdir(parents=True)
    path.write_text(contents)


def result_signature(kind, root, input_rows, unique=20, warmups=0, shape="domains"):
    database = root / "ioc-dataframe.db"
    with sqlite3.connect(database) as connection:
        if kind == "document":
            names = ["masks.csv", "ip-list.csv", "address-blacklist.csv",
                     "hashes.csv", "IOC_aggregate.csv"]
            projections = {name: hashlib.sha256((root / name).read_bytes()).hexdigest()
                           for name in names}
            keys = {artifact: connection.execute(
                f"SELECT row_key FROM {artifact} ORDER BY row_key").fetchall()
                for artifact in ("masks", "ip_list", "address_blacklist", "hashes",
                                 "ioc_aggregate")}
            expected_counts = expected_document_counts(input_rows, unique, shape)
            if any(len(keys[name]) != count * (warmups + 1)
                   for name, count in expected_counts.items()):
                raise RuntimeError("Document did not retain the expected typed indicator set")
            provenance = {artifact: connection.execute(
                f"SELECT row_id, source_key, occurrences FROM {artifact}_sources "
                "ORDER BY row_id, source_key").fetchall() for artifact in keys}
            ids = {artifact: connection.execute(
                f"SELECT id, row_key FROM {artifact} ORDER BY id").fetchall()
                   for artifact in keys}
            return {"projections": projections, "canonical_keys": keys,
                    "canonical_ids": ids, "provenance": provenance}
        expected_values = set(fixture_values(min(input_rows, unique), shape, "import"))
        rows = [row for row in connection.execute(
            "SELECT mask, row_key, url_match, host_match, source FROM masks ORDER BY mask")
                if row[0] in expected_values]
        receipt = connection.execute("SELECT accepted_rows, rejected_rows, "
                                     "public_mutations FROM import_commit WHERE delivery_id = 'comparison-import'").fetchall()
        expected = min(input_rows, unique)
        stages = list((root / "staging").glob("*.sealed.db"))
        if len(stages) != warmups + 1:
            raise RuntimeError("Expected one sealed import stage")
        selected_stages = []
        for path in stages:
            with sqlite3.connect(path) as stage:
                if stage.execute("SELECT delivery_id FROM stage_meta").fetchall() == [("comparison-import",)]:
                    selected_stages.append(path)
        if len(selected_stages) != 1:
            raise RuntimeError("Expected one measured delivery stage")
        with sqlite3.connect(selected_stages[0]) as stage:
            statuses = dict(stage.execute(
                "SELECT status, COUNT(*) FROM stage_input_row GROUP BY status"))
            stage_warnings = stage.execute(
                "SELECT source_row_number, artifact, diagnostic_code FROM stage_row_warning "
                "ORDER BY source_row_number, artifact, diagnostic_code").fetchall()
        receipt_warnings = connection.execute(
            "SELECT source_row_number, artifact, diagnostic_code FROM import_row_warning "
            "WHERE delivery_id = 'comparison-import' "
            "ORDER BY source_row_number, artifact, diagnostic_code").fetchall()
        receipt_outcome = connection.execute(
            "SELECT outcome FROM import_commit WHERE delivery_id = 'comparison-import'").fetchall()
        with sqlite3.connect(root / "service.db") as service:
            terminal = service.execute(
                "SELECT state, terminal_outcome FROM import_delivery WHERE delivery_id = 'comparison-import'").fetchall()
        expected_statuses = {"ACCEPTED": expected}
        if input_rows > expected:
            expected_statuses["COALESCED"] = input_rows - expected
        if len(rows) != expected or receipt != [(expected, 0, expected)] \
                or statuses != expected_statuses or stage_warnings != receipt_warnings \
                or terminal != [("TERMINAL", "SUCCEEDED")]:
            raise RuntimeError("Incomplete canonical import result")
        return {"rows": rows, "receipt_counts": receipt, "stage_statuses": statuses,
                "stage_warnings": stage_warnings, "receipt_warnings": receipt_warnings,
                "receipt_outcome": receipt_outcome, "terminal_state": terminal}


def expected_document_diagnostics(input_rows, unique, shape, selected):
    values = fixture_values(unique, shape)
    overlaps = sum(values[index % unique].startswith("https://") for index in range(input_rows))
    return overlaps + (0 if selected else input_rows - min(input_rows, unique))


def run_one(workspace, fixture, kind, selected, iteration, classpath, input_rows, unique, warmups,
            resources=RESOURCES, diagnostics=False, shape="domains"):
    name = "selected" if selected else "compatible"
    root = workspace / f"{kind}-{name}-{iteration}"
    root.mkdir()
    config(kind, selected, root, resources, shape)
    warmup_paths = []
    for number in range(warmups):
        warmup_root = root / f"warmup-{number}"
        warmup_root.mkdir()
        paths = fixtures(warmup_root, input_rows, input_rows, unique, unique, shape, number + 1)
        path = warmup_root / f"warmup-{number}{fixture.suffix}"
        paths[kind].rename(path)
        warmup_paths.append(path)
    profiles = "golden"
    args = ["java", "-Xms128m", "-Xmx512m", f"-Dspring.profiles.active={profiles}",
            f"-Dspring.config.additional-location=file:{root}/configs/application.yml",
            "-cp", classpath, "com.iocextractor.bootstrap.ProcessingRouteComparison",
            kind, str(root), str(fixture), *map(str, warmup_paths)]
    if diagnostics:
        args[1:1] = [f"-javaagent:{workspace}/comparison-diagnostics.jar",
                     "-Dcomparison.diagnostics=true",
                     f"-XX:StartFlightRecording=filename={root}/diagnostic.jfr,settings=profile,dumponexit=true"]
    fork_start = time.monotonic()
    output = command(args, cwd=root, output=root / "run.log")
    fork_elapsed = time.monotonic() - fork_start
    found = METRIC.findall(output)
    if len(found) != 1:
        raise RuntimeError(f"Missing comparison metric in {root}/run.log")
    metrics = dict(item.split("=", 1) for item in found[0].split())
    if int(metrics["observations"]) != input_rows:
        raise RuntimeError(f"Expected {input_rows} observations; got {metrics['observations']}")
    environments = re.findall(r"^ROUTE_ENV (.+)$", output, re.MULTILINE)
    if len(environments) != 1:
        raise RuntimeError("Missing environment metric")
    metrics.update(dict(item.split("=", 1) for item in environments[0].split()))
    if diagnostics:
        counters = diagnostic_counters(output)
        metrics["diagnostic_counters"] = counters
        metrics["preparation_ms"] = str(counters["preparation_nanos"] / 1_000_000)
    metrics["kind"], metrics["path"], metrics["iteration"] = kind, name, iteration
    metrics["config_sha256"] = hashlib.sha256((root / "configs/application.yml").read_bytes()).hexdigest()
    metrics["fork_wall_ms"] = str(fork_elapsed * 1000)
    outcomes = re.findall(r"^ROUTE_OUTCOME (.+)$", output, re.MULTILINE)
    if len(outcomes) != warmups + 1:
        raise RuntimeError("Missing measured outcome summary")
    if kind == "document":
        outcome_counts = dict(item.split("=", 1) for item in outcomes[-1].split()[:5])
        # URL spans also produce supported extractor overlap diagnostics in both paths.
        expected_diagnostics = expected_document_diagnostics(input_rows, unique, shape, selected)
        if int(outcome_counts["retained"]) != min(input_rows, unique) or \
                int(outcome_counts["diagnostics"]) != expected_diagnostics:
            raise RuntimeError("Unexpected retained/duplicate diagnostic counts")
    signature = result_signature(kind, root, input_rows, unique, warmups, shape)
    metrics["outcome"] = outcomes[-1]
    signature["outcome"] = outcomes[-1]
    (root / "signature.json").write_text(json.dumps(signature, sort_keys=True, indent=2) + "\n")
    metrics["signature_sha256"] = hashlib.sha256(json.dumps(signature, sort_keys=True).encode()).hexdigest()
    return metrics, signature


def summary(rows):
    columns = ("elapsed_ms", "throughput_per_s", "allocated_main_bytes",
               "sampled_peak_heap_bytes", "sampled_peak_rss_kib", "sampled_peak_current_rss_kib",
               "startup_ms", "gc_count", "gc_time_ms")
    if rows and all("preparation_ms" in row for row in rows):
        columns += ("preparation_ms",)
    result = {}
    for kind in sorted({row["kind"] for row in rows}):
        result[kind] = {}
        for column in columns:
            baseline = [float(row[column]) for row in rows
                        if row["kind"] == kind and row["path"] == "compatible"]
            selected = [float(row[column]) for row in rows
                        if row["kind"] == kind and row["path"] == "selected"]
            ratios = [s / b for b, s in zip(baseline, selected) if b > 0]
            result[kind][column] = {
                "compatible_median": statistics.median(baseline),
                "selected_median": statistics.median(selected),
                "selected_over_compatible": statistics.median(selected)
                / statistics.median(baseline) if statistics.median(baseline) else None,
                "compatible_min": min(baseline), "compatible_max": max(baseline),
                "selected_min": min(selected), "selected_max": max(selected),
                "paired_ratios": ratios}
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--document-rows", type=int, default=8000)
    parser.add_argument("--import-rows", type=int, default=2000)
    parser.add_argument("--pairs", type=int, default=5)
    parser.add_argument("--workload", choices=("both", "document", "import"), default="both")
    parser.add_argument("--shape", choices=("domains", "mixed", "long"), default="domains",
                        help="Mixed/long use original-view routing for matched public fields")
    parser.add_argument("--document-unique", type=int, default=20)
    parser.add_argument("--import-unique", type=int, default=20)
    parser.add_argument("--warmups", type=int, default=0,
                        help="Disjoint warm-up files per JVM before the measured insertion workload")
    parser.add_argument("--diagnostics", action="store_true",
                        help="Separate instrumented preparation/counter/JFR forks; not primary cost evidence")
    parser.add_argument("--max-wall-ratio", type=float, default=2.0)
    parser.add_argument("--max-allocation-ratio", type=float, default=3.0)
    parser.add_argument("--max-rss-kib", type=int, default=1048576)
    parser.add_argument("--max-memory-ratio", type=float, default=1.25)
    parser.add_argument("--workspace", type=Path, default=REPO / ".dev/processing-route-comparison")
    args = parser.parse_args()
    if args.warmups < 0:
        parser.error("Warmups must be nonnegative")
    if min(args.document_rows, args.import_rows, args.pairs, args.document_unique, args.import_unique) <= 0:
        parser.error("All counts must be positive")
    if min(args.max_wall_ratio, args.max_allocation_ratio,
           args.max_memory_ratio) < 1 or args.max_rss_kib <= 0:
        parser.error("Limits must be positive ratios >= 1 and positive RSS")
    workspace = args.workspace.resolve()
    if not workspace.is_relative_to(REPO / ".dev"):
        parser.error("Workspace must be below repo-local .dev")
    workspace.mkdir(parents=True, exist_ok=True)
    if list(workspace.iterdir()):
        parser.error("Workspace must be empty; use a new path for each measurement")
    identity = source_identity()
    command([str(REPO / "mvnw"), "-B", "-ntp", "-pl", "bootstrap/ioc-app", "-am",
             "package", "-DskipTests", "-Dcheckstyle.skip", "-Dspotbugs.skip",
             "-Djacoco.skip"], output=workspace / "compile.log", timeout=1200)
    if source_identity() != identity:
        raise RuntimeError("Sources changed during compilation; use an isolated worktree")
    jars = list((APP / "target").glob("ioc-app-*.jar"))
    if len(jars) != 1:
        raise RuntimeError("Expected one bootable app jar")
    jar_digest = hashlib.sha256(jars[0].read_bytes()).hexdigest()
    libraries = workspace / "lib"
    libraries.mkdir()
    app_classes = workspace / "app-classes"
    app_classes.mkdir()
    with zipfile.ZipFile(jars[0]) as archive:
        for member in archive.namelist():
            if member.startswith("BOOT-INF/lib/") and member.endswith(".jar"):
                (libraries / Path(member).name).write_bytes(archive.read(member))
            elif member.startswith("BOOT-INF/classes/") and not member.endswith("/"):
                target = app_classes / member.removeprefix("BOOT-INF/classes/")
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(archive.read(member))
    probe = workspace / "probe-classes/com/iocextractor/bootstrap"
    probe.mkdir(parents=True)
    for compiled in (APP / "target/test-classes/com/iocextractor/bootstrap").glob(
            "ProcessingRouteComparison*.class"):
        shutil.copy2(compiled, probe / compiled.name)
    if args.diagnostics:
        with zipfile.ZipFile(workspace / "comparison-diagnostics.jar", "w") as agent:
            agent.writestr("META-INF/MANIFEST.MF",
                           "Manifest-Version: 1.0\nPremain-Class: com.iocextractor.bootstrap.ComparisonDiagnostics\n\n")
            for compiled in (APP / "target/test-classes/com/iocextractor/bootstrap").glob(
                    "ComparisonDiagnostics*.class"):
                agent.write(compiled, "com/iocextractor/bootstrap/" + compiled.name)
                shutil.copy2(compiled, probe / compiled.name)
    resources = workspace / "test-resources"
    shutil.copytree(RESOURCES, resources)
    classes = [workspace / "probe-classes", resources, app_classes]
    classpath = ":".join(map(str, classes + sorted(libraries.glob("*.jar"))))
    inputs = fixtures(workspace, args.document_rows, args.import_rows,
                      args.document_unique, args.import_unique, args.shape)
    if args.workload != "both":
        inputs = {args.workload: inputs[args.workload]}
    rows = []
    try:
        for kind, fixture in inputs.items():
            for iteration in range(args.pairs):
                pair = []
                for selected in ([False, True] if iteration % 2 == 0 else [True, False]):
                    print(f"{kind} pair={iteration + 1} path={'selected' if selected else 'compatible'}",
                          flush=True)
                    metrics, signature = run_one(workspace, fixture, kind, selected, iteration,
                                                 classpath, args.document_rows if kind == "document"
                                                 else args.import_rows,
                                                 args.document_unique if kind == "document" else args.import_unique,
                                                 args.warmups, resources, args.diagnostics, args.shape)
                    rows.append(metrics)
                    (workspace / "samples.json").write_text(json.dumps(rows, indent=2) + "\n")
                    pair.append(signature)
                if {key: value for key, value in pair[0].items() if key != "outcome"} != \
                        {key: value for key, value in pair[1].items() if key != "outcome"}:
                    raise RuntimeError(f"{kind} output differs in pair {iteration + 1}")
    except Exception as failure:
        (workspace / "failure.json").write_text(json.dumps({
            **identity, "profile": vars(args) | {"workspace": str(workspace)},
            "completed_metrics": rows, "error": str(failure),
            "equivalent_results": False, "acceptance": "invalid measurement"}, indent=2) + "\n")
        raise
    medians = summary(rows)
    limits = {"max_wall_ratio": args.max_wall_ratio,
              "max_allocation_ratio": args.max_allocation_ratio,
              "max_rss_kib": args.max_rss_kib,
              "max_memory_ratio": args.max_memory_ratio}
    passing = all(
        medians[kind]["elapsed_ms"]["selected_over_compatible"] <= args.max_wall_ratio
        and medians[kind]["allocated_main_bytes"]["selected_over_compatible"]
        <= args.max_allocation_ratio
        and medians[kind]["sampled_peak_heap_bytes"]["selected_over_compatible"]
        <= args.max_memory_ratio
        and medians[kind]["sampled_peak_rss_kib"]["selected_over_compatible"]
        <= args.max_memory_ratio
        and all(float(row["sampled_peak_rss_kib"]) <= args.max_rss_kib
                for row in rows if row["kind"] == kind and row["path"] == "selected")
        for kind in inputs)
    report = {**identity,
              "bootable_jar_sha256": jar_digest,
              "java": command(["java", "-version"]).splitlines()[0],
              "host": platform.platform(),
              "warmups": args.warmups, "pairs": args.pairs,
              "measurement_mode": "diagnostic-instrumentation-and-jfr" if args.diagnostics else "primary",
              "logging_environment_keys": sorted(key for key in os.environ
                                                 if key in ("DEBUG", "TRACE") or key.startswith("LOGGING_")),
              "logging_controls": {key: os.environ.get(key, "unset")
                                   for key in ("DEBUG", "TRACE", "LOGGING_LEVEL_ROOT")},
              "shape": args.shape,
              "routing_semantics": "host cleanup" if args.shape == "domains" else "original view",
              "cpu_count": os.cpu_count(),
              "cgroup_limits": {name: Path(f"/sys/fs/cgroup/{name}").read_text().strip()
                                if Path(f"/sys/fs/cgroup/{name}").exists() else "unavailable"
                                for name in ("cpu.max", "memory.max")},
              "jvm_flags": ["-Xms128m", "-Xmx512m"],
              "runtime_jars": [p.name for p in sorted(libraries.glob("*.jar"))],
              "acceptance_budget": "not agreed; limits are historical regression guards",
              "inputs": {kind: {"rows": args.document_rows if kind == "document" else args.import_rows,
                                "unique": min(args.document_rows, args.document_unique) if kind == "document"
                                else min(args.import_rows, args.import_unique),
                                "duplicate_fraction": 1 - min(
                                    args.document_rows, args.document_unique) / args.document_rows
                                    if kind == "document" else 1 - min(
                                        args.import_rows, args.import_unique) / args.import_rows,
                                "sha256": hashlib.sha256(path.read_bytes()).hexdigest()}
                         for kind, path in inputs.items()},
              "metrics": rows, "medians": medians, "limits": limits,
              "equivalent_results": True, "within_provisional_envelope": passing}
    (workspace / "report.json").write_text(json.dumps(report, indent=2) + "\n")
    print(f"Equivalent results in all {args.pairs} pairs per workload; "
          f"provisional envelope {'passed' if passing else 'FAILED'}; "
          f"{workspace}/report.json")
    if not passing and not args.diagnostics:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
