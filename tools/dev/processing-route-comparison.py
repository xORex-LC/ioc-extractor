#!/usr/bin/env python3
"""Router-only IOC qualification; historical paired-report statistics remain readable."""

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
from urllib.parse import urlsplit
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


def fixture_values(unique, shape="domains", kind="document", offset=0, collapse_hosts=20):
    """Independent, disjoint identities; shape changes never silently change routing policy."""
    values = []
    for index in range(unique):
        if shape == "host-collapse":
            host_index = offset * collapse_hosts + index % collapse_hosts
            host = (f"benchmark-{host_index}.example.com" if host_index % 2 == 0
                    else f"198.18.{host_index // 256}.{host_index % 256}")
            if host_index >= 65536:
                raise ValueError("Host-collapse IPv4 fixture exceeds the benchmark address range")
            scheme = "https" if index // collapse_hosts % 2 == 0 else "http"
            values.append(f"{scheme}://{host}:{8000 + index % 1000}/endpoint/{index}"
                          f"?variant={index}&download=true#section")
            continue
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
             shape="domains", offset=0, collapse_hosts=20):
    values = fixture_values(document_unique, shape, "document", offset, collapse_hosts)
    document = workspace / "document.html"
    if shape == "host-collapse":
        document.write_text("<html><body>\n" + "".join(
            f"<h2>БИБ-000{section + 1}</h2>\n" + "".join(
                f"<p>{values[(index // 2) % len(values)]}</p>\n"
                for index in range(document_rows // 2)) for section in range(2)) + "</body></html>\n")
        values = fixture_values(import_unique, shape, "import", offset, collapse_hosts)
        csv = workspace / "import.csv"
        csv.write_text("ioc;source\n" + "".join(
            f"{values[(index // 2) % len(values)]};Feed {'Alpha' if index // 2 % collapse_hosts % 2 == 0 else 'Beta'}\n"
            for index in range(import_rows)))
        return {"document": document, "import": csv}
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


def config(kind, selected, target, resources=RESOURCES, shape="domains", capacity=False):
    if not selected:
        raise ValueError("The compatible processing engine is retired; use Router-only qualification")
    if kind == "document":
        contents = (resources / "application-customer-routes.yml").read_text()
    else:
        contents = (resources / "application-selected-import-production.yml").read_text()
    if shape == "host-collapse":
        # Complete artifact elements preserve strict binder list ownership. Both
        # document/import admit cleaned IPv4 in masks as well as domain hosts.
        contents = contents.replace("default-view: original", "default-view: host")
        policy = (resources / "application-golden.yml").read_text().split("  sink:\n", 1)[1]
        contents += "  sink:\n" + policy.replace("exclude: [ is-bare-ip ]", "exclude: []")
    elif selected and shape != "domains":
        # Matched output semantics for mixed/long profiles; cleanup is qualified separately.
        contents = contents.replace("default-view: host", "default-view: original")
    if capacity and kind == "document":
        # The ordinary golden profile leaves lifecycle disabled. Capacity must
        # exercise canonical aliases and confirmation, as the daemon does.
        contents += "  lifecycle:\n    validity:\n      mode: fixed\n"
    path = target / "configs/application.yml"
    path.parent.mkdir(parents=True)
    path.write_text(contents)


def canonical_import_accounting(connection):
    return {
        "canonical_ids": connection.execute("SELECT id, row_key FROM masks ORDER BY id").fetchall(),
        "provenance": connection.execute(
            "SELECT row_id, source_key, occurrences FROM masks_sources ORDER BY row_id, source_key").fetchall()}


def canonical_digest(values):
    return hashlib.sha256(json.dumps(values, separators=(",", ":")).encode()).hexdigest()


def capacity_document_fields(connection, input_rows, unique, warmups, shape):
    """Check every public field/key independently; stream actual rows."""
    if shape not in ("domains", "mixed"):
        raise ValueError("Capacity oracle requires domains or mixed fixtures")
    names = ("masks", "ip_list", "address_blacklist", "hashes", "ioc_aggregate")
    expected = {name: {} for name in names}
    for offset in range(warmups + 1):
        for index, value in enumerate(fixture_values(unique, shape, offset=offset)[:input_rows]):
            variant = index % 4 if shape == "mixed" else 0
            domain, ip, url, md5 = (variant == n for n in range(4))
            if domain or url:
                expected["masks"][canonical_digest([value])] = (
                    value, "u:hEX" if domain else "u:hEX,dEX", "h:dEX" if domain else None,
                    None, None, None, None, "БИБ-0001", None)
            if ip:
                expected["ip_list"][canonical_digest([value])] = (
                    value, None, None, None, None, "БИБ-0001", None)
            if not md5:
                carriers = [None, value] if ip else [value, None]
                expected["address_blacklist"][canonical_digest(carriers)] = tuple(carriers)
            else:
                expected["hashes"][canonical_digest([value.upper(), None, None])] = (
                    value.upper(), None, None, None, None, None, None, "БИБ-0001", None)
            carriers = [value if ip else None, value if url else None,
                        value if domain else None, value.upper() if md5 else None]
            expected["ioc_aggregate"][canonical_digest(carriers)] = ("БИБ-0001", *carriers)
    columns = {"masks": "mask,url_match,host_match,score,time_last_seen,time_first_seen,threat_type,source,description",
               "ip_list": "ip,score,time_last_seen,time_first_seen,threat_type,source,description",
               "address_blacklist": "forbidden_url,forbidden_ip",
               "hashes": "hash_md5,hash_sha256,hash_sha1,score,time_last_seen,time_first_seen,threat_type,source,description",
               "ioc_aggregate": "name,ip_address,url_match,host_match,hash"}
    digests = {}
    for artifact in names:
        digest = hashlib.sha256()
        for actual in connection.execute(f"SELECT row_key,{columns[artifact]} FROM {artifact} ORDER BY row_key"):
            key, *values = actual
            values = tuple(None if value == "NULL" else value for value in values)
            if values != expected[artifact].pop(key, None):
                raise RuntimeError(f"Capacity oracle: wrong fields or key in {artifact}")
            digest.update(json.dumps([key, *values], ensure_ascii=False, separators=(",", ":")).encode())
            digest.update(b"\n")
        if expected[artifact]:
            raise RuntimeError(f"Capacity oracle: missing rows in {artifact}")
        digests[artifact] = digest.hexdigest()
    return digests


def host_collapse_fields(connection, kind, hosts, warmups):
    """Independent final-field/key/winner oracle; never uses the production parser or mapper."""
    expected = {name: [] for name in ("masks", "ip_list", "address_blacklist", "ioc_aggregate")}
    for offset in range(warmups + 1):
        for index in range(hosts):
            identity = offset * hosts + index
            ip = index % 2 == 1
            host = f"198.18.{identity // 256}.{identity % 256}" if ip else f"benchmark-{identity}.example.com"
            source = ("Feed Beta" if ip else "Feed Alpha") if kind == "import" else "БИБ-0001"
            expected["masks"].append((host, "u:hAS" if ip else "u:hEX",
                                      "h:dAS" if ip else "h:dEX", source, canonical_digest([host])))
            if ip:
                expected["ip_list"].append((host, source, canonical_digest([host])))
            carriers = [None, host] if ip else [host, None]
            expected["address_blacklist"].append((*carriers, canonical_digest(carriers)))
            aggregate = [host, None, None, None] if ip else [None, None, host, None]
            expected["ioc_aggregate"].append(("БИБ-0002", *aggregate, canonical_digest(aggregate)))
    columns = {"masks": "mask, url_match, host_match, source, row_key",
               "ip_list": "ip, source, row_key",
               "address_blacklist": "forbidden_url, forbidden_ip, row_key",
               "ioc_aggregate": "name, ip_address, url_match, host_match, hash, row_key"}
    result = {}
    for artifact in (["masks"] if kind == "import" else expected):
        rows = [tuple(None if value == "NULL" else value for value in row)
                for row in connection.execute(f"SELECT {columns[artifact]} FROM {artifact}")]
        if sorted(rows, key=str) != sorted(expected[artifact], key=str):
            raise RuntimeError(f"Host-collapse {kind}/{artifact} final fields, keys or winner differ")
        expected_source = ("dataframe-import:local-hosts" if kind == "import" else
                           "БИБ-0002" if artifact == "ioc_aggregate" else "БИБ-0001")
        provenance = connection.execute(
            f"SELECT row_id, source_key, occurrences FROM {artifact}_sources ORDER BY row_id").fetchall()
        canonical_ids = {row[0] for row in connection.execute(f"SELECT id FROM {artifact}")}
        if len(provenance) != len(rows) or {row[0] for row in provenance} != canonical_ids \
                or any(row[1:] != (expected_source, 1) for row in provenance):
            raise RuntimeError(f"Host-collapse {artifact} winner provenance accounting differs")
        result[artifact] = sorted(rows, key=str)
    return result


def result_signature(kind, root, input_rows, unique=20, warmups=0, shape="domains", collapse_hosts=20):
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
            expected_counts = (dict(masks=collapse_hosts, ip_list=collapse_hosts // 2,
                                    address_blacklist=collapse_hosts, hashes=0, ioc_aggregate=collapse_hosts)
                               if shape == "host-collapse" else expected_document_counts(input_rows, unique, shape))
            if any(len(keys[name]) != count * (warmups + 1)
                   for name, count in expected_counts.items()):
                raise RuntimeError("Document did not retain the expected typed indicator set")
            provenance = {artifact: connection.execute(
                f"SELECT row_id, source_key, occurrences FROM {artifact}_sources "
                "ORDER BY row_id, source_key").fetchall() for artifact in keys}
            ids = {artifact: connection.execute(
                f"SELECT id, row_key FROM {artifact} ORDER BY id").fetchall()
                   for artifact in keys}
            fields = host_collapse_fields(connection, kind, collapse_hosts, warmups) \
                if shape == "host-collapse" else {}
            return {"projections": projections, "canonical_keys": keys,
                    "canonical_ids": ids, "provenance": provenance, "final_fields": fields}
        expected_values = set(fixture_values(min(input_rows, unique), shape, "import",
                                            collapse_hosts=collapse_hosts))
        if shape == "host-collapse":
            expected_values = {urlsplit(value).hostname for value in expected_values}
        rows = [row for row in connection.execute(
            "SELECT mask, row_key, url_match, host_match, source FROM masks ORDER BY mask")
                if row[0] in expected_values]
        receipt = connection.execute("SELECT accepted_rows, rejected_rows, "
                                     "public_mutations FROM import_commit WHERE delivery_id = 'comparison-import'").fetchall()
        expected = collapse_hosts if shape == "host-collapse" else min(input_rows, unique)
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
        fields = host_collapse_fields(connection, kind, collapse_hosts, warmups) \
            if shape == "host-collapse" else {}
        return {**canonical_import_accounting(connection), "final_fields": fields,
                "rows": rows, "receipt_counts": receipt, "stage_statuses": statuses,
                "stage_warnings": stage_warnings, "receipt_warnings": receipt_warnings,
                "receipt_outcome": receipt_outcome, "terminal_state": terminal}


def expected_document_diagnostics(input_rows, unique, shape, selected):
    values = fixture_values(unique, shape)
    overlaps = sum(values[index % unique].startswith(("https://", "http://")) for index in range(input_rows))
    return overlaps + (0 if selected else input_rows - min(input_rows, unique))


def run_one(workspace, fixture, kind, selected, iteration, classpath, input_rows, unique, warmups,
            resources=RESOURCES, diagnostics=False, shape="domains", collapse_hosts=20, capacity=False):
    name = "selected" if selected else "compatible"
    root = workspace / f"{kind}-{name}-{iteration}"
    root.mkdir()
    config(kind, selected, root, resources, shape, capacity)
    warmup_paths = []
    for number in range(warmups):
        warmup_root = root / f"warmup-{number}"
        warmup_root.mkdir()
        paths = fixtures(warmup_root, input_rows, input_rows, unique, unique, shape, number + 1, collapse_hosts)
        path = warmup_root / f"warmup-{number}{fixture.suffix}"
        paths[kind].rename(path)
        warmup_paths.append(path)
    profiles = "golden"
    args = ["java", "-Xms128m", "-Xmx512m", f"-Dspring.profiles.active={profiles}",
            f"-Dspring.config.additional-location=file:{root}/configs/application.yml",
            "-cp", classpath, "com.iocextractor.bootstrap.ProcessingRouteComparison",
            kind, str(root), str(fixture), *map(str, warmup_paths)]
    if capacity:
        args[1:1] = ["-Dlogging.config=classpath:logback-capacity.xml"]
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
        if shape == "host-collapse" and (outcome_counts["status"] != "COMPLETED"
                or f"severities={{DEBUG={input_rows}}}" not in outcomes[-1]
                or "EXTRACTION.INDICATOR_SKIPPED:DEBUG=" not in outcomes[-1]):
            raise RuntimeError("Unexpected host-collapse diagnostic severity or code")
    signature = result_signature(kind, root, input_rows, unique, warmups, shape, collapse_hosts)
    if capacity:
        with sqlite3.connect(root / "ioc-dataframe.db") as connection:
            if kind == "document":
                signature["independent_public_field_digests"] = capacity_document_fields(
                    connection, input_rows, unique, warmups, shape)
                for artifact, keys in signature["canonical_keys"].items():
                    active = connection.execute(f"SELECT COUNT(*) FROM {artifact} "
                        "WHERE _lifecycle_id IS NOT NULL AND _valid_until_epoch_ms IS NOT NULL").fetchone()[0]
                    if active != len(keys):
                        raise RuntimeError(f"Capacity profile bypassed lifecycle for {artifact}")
            metrics["dataframe_schema_version"] = connection.execute("PRAGMA user_version").fetchone()[0]
        if kind == "document":
            completed = [json.loads(line) for line in output.splitlines() if line.startswith('{"@timestamp"')]
            phases = [item for item in completed if item.get("event", {}).get("action") == "stage_complete"]
            if len(phases) != 6 * (warmups + 1):
                raise RuntimeError("Missing capacity pipeline phase anchors")
            metrics["pipeline_phases_nanos"] = {
                item["ioc"]["stage"]: item["event"]["duration"] for item in phases[-6:]}
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
                "compatible_median": statistics.median(baseline) if baseline else None,
                "selected_median": statistics.median(selected),
                "selected_over_compatible": statistics.median(selected)
                / statistics.median(baseline) if baseline and statistics.median(baseline) else None,
                "compatible_min": min(baseline) if baseline else None,
                "compatible_max": max(baseline) if baseline else None,
                "selected_min": min(selected), "selected_max": max(selected),
                "paired_ratios": ratios}
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--document-rows", type=int, default=8000)
    parser.add_argument("--import-rows", type=int, default=2000)
    parser.add_argument("--pairs", type=int, default=5)
    parser.add_argument("--workload", choices=("both", "document", "import"), default="both")
    parser.add_argument("--shape", choices=("domains", "mixed", "long", "host-collapse"), default="domains",
                        help="Mixed/long use original-view routing for matched public fields")
    parser.add_argument("--selected-only", action="store_true", default=True,
                        help="Router-only qualification (required since the complete cutover)")
    parser.add_argument("--collapse-hosts", type=int, default=20)
    parser.add_argument("--document-unique", type=int, default=20)
    parser.add_argument("--import-unique", type=int, default=20)
    parser.add_argument("--warmups", type=int, default=0,
                        help="Disjoint warm-up files per JVM before the measured insertion workload")
    parser.add_argument("--diagnostics", action="store_true",
                        help="Separate instrumented preparation/counter/JFR forks; not primary cost evidence")
    parser.add_argument("--capacity", action="store_true",
                        help="Exercise active lifecycle/alias mutations for canonical capacity qualification")
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
    if args.shape == "host-collapse" and (args.collapse_hosts <= 0 or args.collapse_hosts % 2
            or args.document_unique < args.collapse_hosts or args.import_unique < args.collapse_hosts
            or args.document_unique % args.collapse_hosts or args.import_unique % args.collapse_hosts
            or args.document_rows % (4 * args.document_unique)
            or args.import_rows % (2 * args.import_unique)):
        parser.error("Host-collapse needs positive even hosts, unique URL counts divisible by hosts, "
                     "document rows divisible by 4*unique and import rows by 2*unique")
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
                      args.document_unique, args.import_unique, args.shape, collapse_hosts=args.collapse_hosts)
    if args.workload != "both":
        inputs = {args.workload: inputs[args.workload]}
    rows = []
    previous = {}
    try:
        for kind, fixture in inputs.items():
            for iteration in range(args.pairs):
                print(f"{kind} run={iteration + 1} path=selected", flush=True)
                metrics, signature = run_one(workspace, fixture, kind, True, iteration,
                                             classpath, args.document_rows if kind == "document"
                                             else args.import_rows,
                                             args.document_unique if kind == "document" else args.import_unique,
                                             args.warmups, resources, args.diagnostics, args.shape,
                                             args.collapse_hosts, args.capacity)
                rows.append(metrics)
                (workspace / "samples.json").write_text(json.dumps(rows, indent=2) + "\n")
                if signature != previous.get(kind, signature):
                    raise RuntimeError(f"{kind} Router outcome differs in run {iteration + 1}")
                previous[kind] = signature
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
    passing = None  # Historical compatible ratios cannot qualify the mandatory Router path.
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
              "capacity": args.capacity,
              "routing_semantics": "host cleanup" if args.shape in ("domains", "host-collapse") else "original view",
              "selected_only": args.selected_only,
              "collapse_hosts": args.collapse_hosts if args.shape == "host-collapse" else None,
              "cpu_count": os.cpu_count(),
              "cgroup_limits": {name: Path(f"/sys/fs/cgroup/{name}").read_text().strip()
                                if Path(f"/sys/fs/cgroup/{name}").exists() else "unavailable"
                                for name in ("cpu.max", "memory.max")},
              "jvm_flags": ["-Xms128m", "-Xmx512m"],
              "runtime_jars": [p.name for p in sorted(libraries.glob("*.jar"))],
              "acceptance_budget": "not agreed; limits are historical regression guards",
              "inputs": {kind: {"rows": args.document_rows if kind == "document" else args.import_rows,
                                "final_hosts": args.collapse_hosts if args.shape == "host-collapse" else None,
                                "source_labels": 2 if args.shape == "host-collapse" else 1,
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
    print(f"Equivalent results in all {args.pairs} Router runs per workload; "
          f"provisional envelope {'not evaluated (qualification only)' if passing is None else 'passed' if passing else 'FAILED'}; "
          f"{workspace}/report.json")
    if passing is False and not args.diagnostics:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
