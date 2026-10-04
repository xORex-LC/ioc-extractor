#!/usr/bin/env python3
"""G4 incremental workspace/canonical/receipt diagnostic with mandatory private-state cleanup."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import shutil
import subprocess
import tempfile
import time
import xml.etree.ElementTree as ET
import zipfile

REPO = Path(__file__).resolve().parents[2]
FLAGS = ["-Xms32m", "-Xmx64m"]
UPSTREAM_FLAGS = ["-Xms32m", "-Xmx512m"]


def command(arguments, timeout=120):
    result = subprocess.run(arguments, cwd=REPO, text=True, stdout=subprocess.PIPE,
                            stderr=subprocess.STDOUT, timeout=timeout, check=False)
    if result.returncode:
        raise RuntimeError(f"Command failed ({result.returncode}): {arguments[0]}\n{result.stdout[-16000:]}")
    return result.stdout


def freeze(root):
    """Freeze current compiled module bytecode before dependency jars on the classpath."""
    ns = {"m": "http://maven.apache.org/POM/4.0.0"}
    modules = [item.text for item in ET.parse(REPO / "pom.xml").findall("m:modules/m:module", ns)]
    entries = []
    for index, module in enumerate(modules):
        source = REPO / module / "target/classes"
        if source.is_dir():
            target = root / f"classes-{index}"
            shutil.copytree(source, target)
            entries.append(target)
    jars = list((REPO / "bootstrap/ioc-app/target").glob("ioc-app-*.jar"))
    if len(jars) != 1:
        raise RuntimeError("Build the bootable jar with make verify first")
    libraries = root / "lib"
    libraries.mkdir()
    with zipfile.ZipFile(jars[0]) as archive:
        for entry in archive.namelist():
            if entry.startswith("BOOT-INF/lib/") and entry.endswith(".jar"):
                (libraries / Path(entry).name).write_bytes(archive.read(entry))
    probe = root / "probe/com/iocextractor/bootstrap"
    probe.mkdir(parents=True)
    tests = REPO / "bootstrap/ioc-app/target/test-classes/com/iocextractor/bootstrap"
    shutil.copy2(tests.parents[2] / "logback-capacity.xml", root / "probe/logback-capacity.xml")
    for pattern in ("DocumentWorkspaceCapacity*.class", "DocumentUpstreamCapacity*.class", "ProcessingRouteComparison*.class"):
        files = list(tests.glob(pattern))
        if not files:
            raise RuntimeError("Compile capacity probes with make test-module MODULE=bootstrap/ioc-app")
        for path in files:
            shutil.copy2(path, probe / path.name)
    paths = [root / "probe", *entries, *sorted(libraries.glob("*.jar"))]
    digest = hashlib.sha256()
    for file in sorted(path for path in root.rglob("*") if path.is_file()):
        digest.update(str(file.relative_to(root)).encode())
        with file.open("rb") as contents:
            digest.update(hashlib.file_digest(contents, "sha256").digest())
    return os.pathsep.join(map(str, paths)), digest.hexdigest()


def measure(classpath, size, state, timeout, upstream=False):
    flags = UPSTREAM_FLAGS if upstream else FLAGS
    probe = "DocumentUpstreamCapacity" if upstream else "DocumentWorkspaceCapacity"
    output = command(["java", *flags, "-Dlogging.config=classpath:logback-capacity.xml",
                      "-cp", classpath, "com.iocextractor.bootstrap." + probe,
                      str(size), str(state)], timeout=timeout)
    records = [line.removeprefix("CAPACITY_JSON=") for line in output.splitlines()
               if line.startswith("CAPACITY_JSON=")]
    if len(records) != 1:
        raise RuntimeError("Capacity probe did not return exactly one complete sample\n" + output[-16000:])
    return json.loads(records[0])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--sizes", type=int, nargs="+", default=[10000, 100000, 1000000])
    parser.add_argument("--upstream-sizes", type=int, nargs="*", default=[10000, 100000, 1000000])
    parser.add_argument("--timeout", type=int, default=900)
    args = parser.parse_args()
    if any(size < 1 for size in [*args.sizes, *args.upstream_sizes]):
        parser.error("sizes must be positive")
    head = command(["git", "rev-parse", "HEAD"]).strip()
    if command(["git", "status", "--porcelain"]).strip():
        parser.error("Commit the implementation and probes before qualification")
    report = {"source_commit": head, "scope": "G4 diagnostic: incremental routed candidates through canonical confirmation and receipt replay; explicit GC live samples; no source reader/Router/SMB timing",
              "flags": FLAGS, "platform": platform.platform(), "java": command(["java", "-version"]).strip(),
              "samples": [], "upstream_samples": [], "upstream_flags": UPSTREAM_FLAGS,
              "upstream_scope": "Separate actual Spring/Tika/read/refang/extract/attribute graph; explicit GC stage boundaries; no routing or writes",
              "started_epoch_seconds": time.time()}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="ioc-cap4-") as temporary:
        root = Path(temporary)
        classpath, report["runtime_sha256"] = freeze(root / "runtime")
        for size in args.sizes:
            # Each fork owns a fresh state tree; remove it even on oracle failure or timeout.
            with tempfile.TemporaryDirectory(prefix="state-", dir=root) as state:
                report["samples"].append(measure(classpath, size, Path(state), args.timeout))
            print(f"G4 {size}: semantic oracle passed; private databases removed", flush=True)
            args.output.write_text(json.dumps(report, indent=2) + "\n")
        for size in args.upstream_sizes:
            with tempfile.TemporaryDirectory(prefix="upstream-", dir=root) as state:
                report["upstream_samples"].append(measure(classpath, size, Path(state), args.timeout, upstream=True))
            print(f"Upstream {size}: extraction count passed; private files removed", flush=True)
            args.output.write_text(json.dumps(report, indent=2) + "\n")
    report["temporary_runtime_and_state_removed"] = not root.exists()
    report["completed_epoch_seconds"] = time.time()
    if command(["git", "rev-parse", "HEAD"]).strip() != head:
        raise RuntimeError("HEAD changed during qualification")
    args.output.write_text(json.dumps(report, indent=2) + "\n")


if __name__ == "__main__":
    main()
