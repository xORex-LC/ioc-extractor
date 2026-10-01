#!/usr/bin/env python3
"""Opt-in paired IOC workload comparison using the production Spring composition."""

import argparse
import hashlib
import json
import platform
import re
import shutil
import sqlite3
import statistics
import subprocess
import zipfile
from pathlib import Path


REPO = Path(__file__).resolve().parents[2]
APP = REPO / "bootstrap/ioc-app"
RESOURCES = APP / "src/test/resources"
METRIC = re.compile(r"^ROUTE_COMPARISON (.+)$", re.MULTILINE)


def command(args, *, cwd=REPO, output=None, timeout=600):
    result = subprocess.run(args, cwd=cwd, text=True, stdout=subprocess.PIPE,
                            stderr=subprocess.STDOUT, timeout=timeout, check=False)
    if output:
        output.write_text(result.stdout)
    if result.returncode:
        raise RuntimeError(f"Command failed ({result.returncode}); "
                           f"see {output or 'captured output'}\n{result.stdout[-2500:]}")
    return result.stdout


def fixtures(workspace, document_rows, import_rows):
    values = [f"benchmark-{number}.example.com" for number in range(20)]
    document = workspace / "document.html"
    document.write_text("<html><body><h2>БИБ-0001</h2>\n" + "".join(
        f"<p>{values[index % len(values)]}</p>\n" for index in range(document_rows))
        + "</body></html>\n")
    csv = workspace / "import.csv"
    csv.write_text("ioc;source\n" + "".join(
        f"{values[index % len(values)]};Feed Alpha\n" for index in range(import_rows)))
    return {"document": document, "import": csv}


def config(kind, selected, target):
    if kind == "document":
        contents = (RESOURCES / "application-customer-routes.yml").read_text() \
            if selected else "ioc: {}\n"
    else:
        contents = (RESOURCES / "application-selected-import-production.yml").read_text()
        if not selected:
            begin = contents.index("        processed-route:\n")
            end = contents.index("    runtime:\n", begin)
            contents = contents[:begin] + contents[end:]
    path = target / "configs/application.yml"
    path.parent.mkdir(parents=True)
    path.write_text(contents)


def result_signature(kind, root, input_rows):
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
            if any(len(keys[name]) != min(input_rows, 20)
                   for name in ("masks", "address_blacklist", "ioc_aggregate")):
                raise RuntimeError("Document did not retain the expected 20-domain set")
            return {"projections": projections, "canonical_keys": keys}
        rows = connection.execute(
            "SELECT mask, row_key, url_match, host_match, source "
            "FROM masks ORDER BY mask").fetchall()
        receipt = connection.execute("SELECT accepted_rows, rejected_rows, "
                                     "public_mutations FROM import_commit").fetchall()
        expected = min(input_rows, 20)
        stages = list((root / "staging").glob("*.sealed.db"))
        if len(stages) != 1:
            raise RuntimeError("Expected one sealed import stage")
        with sqlite3.connect(stages[0]) as stage:
            statuses = dict(stage.execute(
                "SELECT status, COUNT(*) FROM stage_input_row GROUP BY status"))
        expected_statuses = {"ACCEPTED": expected}
        if input_rows > expected:
            expected_statuses["COALESCED"] = input_rows - expected
        if len(rows) != expected or receipt != [(expected, 0, expected)] \
                or statuses != expected_statuses:
            raise RuntimeError("Incomplete canonical import result")
        return {"rows": rows, "receipt_counts": receipt, "stage_statuses": statuses}


def run_one(workspace, fixture, kind, selected, iteration, classpath, input_rows):
    name = "selected" if selected else "compatible"
    root = workspace / f"{kind}-{name}-{iteration}"
    root.mkdir()
    config(kind, selected, root)
    profiles = "golden"
    args = ["java", "-Xms128m", "-Xmx512m", f"-Dspring.profiles.active={profiles}",
            f"-Dspring.config.additional-location=file:{root}/configs/application.yml",
            "-cp", classpath, "com.iocextractor.bootstrap.ProcessingRouteComparison",
            kind, str(root), str(fixture)]
    output = command(args, cwd=root, output=root / "run.log")
    found = METRIC.findall(output)
    if len(found) != 1:
        raise RuntimeError(f"Missing comparison metric in {root}/run.log")
    metrics = dict(item.split("=", 1) for item in found[0].split())
    if int(metrics["observations"]) != input_rows:
        raise RuntimeError(f"Expected {input_rows} observations; got {metrics['observations']}")
    metrics["kind"], metrics["path"], metrics["iteration"] = kind, name, iteration
    return metrics, result_signature(kind, root, input_rows)


def summary(rows):
    columns = ("elapsed_ms", "throughput_per_s", "allocated_main_bytes",
               "sampled_peak_heap_bytes", "sampled_peak_rss_kib")
    result = {}
    for kind in ("document", "import"):
        result[kind] = {}
        for column in columns:
            baseline = [float(row[column]) for row in rows
                        if row["kind"] == kind and row["path"] == "compatible"]
            selected = [float(row[column]) for row in rows
                        if row["kind"] == kind and row["path"] == "selected"]
            result[kind][column] = {
                "compatible_median": statistics.median(baseline),
                "selected_median": statistics.median(selected),
                "selected_over_compatible": statistics.median(selected)
                / statistics.median(baseline)}
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--document-rows", type=int, default=8000)
    parser.add_argument("--import-rows", type=int, default=2000)
    parser.add_argument("--pairs", type=int, default=3)
    parser.add_argument("--max-wall-ratio", type=float, default=2.0)
    parser.add_argument("--max-allocation-ratio", type=float, default=3.0)
    parser.add_argument("--max-rss-kib", type=int, default=1048576)
    parser.add_argument("--max-memory-ratio", type=float, default=1.25)
    parser.add_argument("--workspace", type=Path, default=REPO / ".dev/processing-route-comparison")
    args = parser.parse_args()
    if min(args.document_rows, args.import_rows, args.pairs) <= 0:
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
    command([str(REPO / "mvnw"), "-B", "-ntp", "-pl", "bootstrap/ioc-app", "-am",
             "package", "-DskipTests", "-Dcheckstyle.skip", "-Dspotbugs.skip",
             "-Djacoco.skip"], output=workspace / "compile.log", timeout=1200)
    jars = list((APP / "target").glob("ioc-app-*.jar"))
    if len(jars) != 1:
        raise RuntimeError("Expected one bootable app jar")
    libraries = workspace / "lib"
    libraries.mkdir()
    with zipfile.ZipFile(jars[0]) as archive:
        for member in archive.namelist():
            if member.startswith("BOOT-INF/lib/") and member.endswith(".jar"):
                (libraries / Path(member).name).write_bytes(archive.read(member))
    probe = workspace / "probe-classes/com/iocextractor/bootstrap"
    probe.mkdir(parents=True)
    for compiled in (APP / "target/test-classes/com/iocextractor/bootstrap").glob(
            "ProcessingRouteComparison*.class"):
        shutil.copy2(compiled, probe / compiled.name)
    classes = [workspace / "probe-classes", RESOURCES]
    classes += sorted(path for scope in ("platform", "core", "adapters", "bootstrap")
                      for path in (REPO / scope).glob("*/target/classes") if path.is_dir())
    classpath = ":".join(map(str, classes + sorted(libraries.glob("*.jar"))))
    inputs = fixtures(workspace, args.document_rows, args.import_rows)
    rows = []
    for kind, fixture in inputs.items():
        for iteration in range(args.pairs):
            pair = []
            for selected in ([False, True] if iteration % 2 == 0 else [True, False]):
                print(f"{kind} pair={iteration + 1} path={'selected' if selected else 'compatible'}",
                      flush=True)
                metrics, signature = run_one(workspace, fixture, kind, selected, iteration,
                                             classpath, args.document_rows if kind == "document"
                                             else args.import_rows)
                rows.append(metrics)
                pair.append(signature)
            if pair[0] != pair[1]:
                raise RuntimeError(f"{kind} output differs in pair {iteration + 1}")
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
    report = {"commit": command(["git", "rev-parse", "HEAD"]).strip(),
              "dirty": bool(command(["git", "status", "--porcelain"]).strip()),
              "java": command(["java", "-version"]).splitlines()[0],
              "host": platform.platform(),
              "inputs": {kind: {"rows": args.document_rows if kind == "document" else args.import_rows,
                                "sha256": hashlib.sha256(path.read_bytes()).hexdigest()}
                         for kind, path in inputs.items()},
              "metrics": rows, "medians": medians, "limits": limits,
              "equivalent_results": True, "within_provisional_envelope": passing}
    (workspace / "report.json").write_text(json.dumps(report, indent=2) + "\n")
    print(f"Equivalent results in all {args.pairs} pairs per workload; "
          f"provisional envelope {'passed' if passing else 'FAILED'}; "
          f"{workspace}/report.json")
    if not passing:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
