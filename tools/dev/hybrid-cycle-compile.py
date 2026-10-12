#!/usr/bin/env python3
"""Compile the changed JDBC adapter and measurement helpers without quality gates."""
import argparse
from pathlib import Path
import shutil
import subprocess
import zipfile
import hashlib
import json

REPO = Path(__file__).resolve().parents[2]


def jdk_tool(name):
    binary = shutil.which(name)
    if binary:
        return [binary]
    module = {'javac': 'jdk.compiler/com.sun.tools.javac.Main', 'jar': 'jdk.jartool/sun.tools.jar.Main'}[name]
    return ['java', '-m', module]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--baseline-jar', type=Path, default=REPO / '.dev/hybrid-eight/baseline/ioc-app.jar')
    args = parser.parse_args()
    output = args.output.resolve()
    if not output.is_relative_to(REPO / '.dev') or (output / 'ioc-app.jar').exists():
        parser.error('Use a new .dev executable directory')
    output.mkdir(parents=True, exist_ok=True)
    baseline = args.baseline_jar.resolve()
    if not baseline.is_file():
        parser.error('A frozen pre-cycle application JAR is required; this tool does not build the reactor')
    libs = output / 'dependencies'
    libs.mkdir(exist_ok=True)
    with zipfile.ZipFile(baseline) as archive:
        for entry in archive.namelist():
            if entry.startswith('BOOT-INF/lib/') and entry.endswith('.jar'):
                (libs / Path(entry).name).write_bytes(archive.read(entry))
    adapter = REPO / 'adapters/adapter-store-jdbc'
    classes = adapter / 'target/classes'
    cp = ':'.join(str(p) for p in sorted(libs.glob('*.jar'))) + ':' + str(REPO / 'bootstrap/ioc-app/target/classes')
    sources = sorted((adapter / 'src/main/java').rglob('*.java'))
    helper_root = REPO / 'bootstrap/ioc-app'
    helpers = []
    for pattern in ['**/SqliteCapacityApplication.java', '**/SqliteCapacityMetrics.java',
                    '**/ReceiptPayloadDiagnostics.java', '**/StagedCapacityBundle.java']:
        helpers.extend((helper_root / 'src/test/java').glob(pattern))
    source_hashes = {str(p.relative_to(REPO)): hashlib.sha256(p.read_bytes()).hexdigest() for p in sources + helpers}
    subprocess.run([*jdk_tool('javac'), '-source', '21', '-target', '21', '-parameters', '-cp', cp, '-d', str(classes), *map(str, sources)], check=True)
    subprocess.run([*jdk_tool('javac'), '-source', '21', '-target', '21', '-parameters', '-cp', str(classes) + ':' + cp,
                    '-d', str(helper_root / 'target/test-classes'), *map(str, helpers)], check=True)
    adapter_jar = output / 'adapter-store-jdbc.jar'
    subprocess.run([*jdk_tool('jar'), '--create', '--file', str(adapter_jar), '-C', str(classes), '.'], check=True)
    with zipfile.ZipFile(baseline) as source, zipfile.ZipFile(output / 'ioc-app.jar', 'w') as target:
        replaced = 0
        for entry in source.infolist():
            if entry.filename.startswith('BOOT-INF/lib/ioc-adapter-store-jdbc-') and entry.filename.endswith('.jar'):
                target.writestr(entry, adapter_jar.read_bytes()); replaced += 1
            else:
                target.writestr(entry, source.read(entry.filename))
        if replaced != 1:
            raise RuntimeError('Expected exactly one JDBC adapter in frozen executable')
    shutil.copytree(helper_root / 'target/test-classes', output / 'helpers')
    after = {str(p.relative_to(REPO)): hashlib.sha256(p.read_bytes()).hexdigest() for p in sources + helpers}
    if after != source_hashes:
        (output / 'ioc-app.jar').unlink()
        raise RuntimeError('Source changed during compilation; executable snapshot rejected')
    (output / 'compilation.json').write_text(json.dumps(dict(baseline_sha256=hashlib.sha256(baseline.read_bytes()).hexdigest(),
            source_sha256=source_hashes, compiler_flags=['-source', '21', '-target', '21', '-parameters'],
            scope='Changed adapter and four helpers only; upstream classes and resources from frozen baseline'), indent=2) + '\n')
    print(output / 'ioc-app.jar')


if __name__ == '__main__':
    main()
