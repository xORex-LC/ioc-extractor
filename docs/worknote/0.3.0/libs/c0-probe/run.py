#!/usr/bin/env python3
"""Run the C0 characterization probe without modifying reactor sources or scope."""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import xml.etree.ElementTree as ET

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[4]
SOURCE = ROOT / 'bootstrap/ioc-app/src/main/java/com/iocextractor/bootstrap'
REPORT = ROOT / 'bootstrap/ioc-app/target/surefire-reports/TEST-com.iocextractor.bootstrap.IocConfigurationOverrideReporterTest.xml'
NAMES = ['IocConfigurationPropertyShape', 'IocEnvironmentPropertyMatcher',
         'IocUnknownConfigurationPreflight', 'IocConfigurationOverrideReporter',
         'IocConfigurationMigrationCatalog']


def main():
    if not REPORT.exists():
        raise SystemExit('First run: make test-one MODULE=bootstrap/ioc-app TEST=IocConfigurationOverrideReporterTest')
    properties = {p.get('name'): p.get('value') for p in ET.parse(REPORT).findall('./properties/property')}
    jdk = Path(os.environ.get('JAVA_HOME', properties['java.home'])) / 'bin'
    if not (jdk / 'javac').is_file():
        raise SystemExit('A full JDK is required; set JAVA_HOME to a JDK 21 installation')
    # Use the exact dependencies of the existing test run, never project class directories.
    prefixes = ('spring-boot-', 'spring-core-', 'spring-context-', 'spring-beans-',
                'spring-expression-', 'spring-aop-', 'slf4j-api-', 'jcl-over-slf4j-',
                'snakeyaml-', 'jspecify-')
    jars = [Path(p) for p in properties['java.class.path'].split(os.pathsep)
            if Path(p).suffix == '.jar' and Path(p).name.startswith(prefixes)]
    # Starter/autoconfigure/test jars are not needed for the direct Binder experiment.
    jars = [p for p in jars if not p.name.startswith('spring-boot-')
            or p.name == 'spring-boot-4.0.8.jar']
    if not any(p.name == 'spring-boot-4.0.8.jar' for p in jars):
        raise SystemExit('This characterization is pinned to Boot 4.0.8; requalify a changed version')
    if any(not p.is_file() for p in jars):
        raise SystemExit('Recorded dependency classpath is incomplete; refresh the focused suite')
    with tempfile.TemporaryDirectory(prefix='ioc-config-c0-') as directory:
        work = Path(directory)
        sources = []
        identity = {}
        for name in NAMES:
            original = (SOURCE / (name + '.java')).read_text()
            text = original
            # Mechanical seams only: preserve matching, scanning and reporting algorithms.
            if name in ('IocEnvironmentPropertyMatcher', 'IocUnknownConfigurationPreflight'):
                assert text.count('private static final String PREFIX = "ioc";') == 1
                text = text.replace('private static final String PREFIX = "ioc";',
                                    'private static final String PREFIX = "collector";')
                assert text.count('IocProperties.class') == 1
                text = text.replace('IocProperties.class', 'ConfigurationContractProbe.schemaType')
            if name == 'IocConfigurationOverrideReporter':
                assert text.count('private static final String PREFIX_DOT = "ioc.";') == 1
                text = text.replace('private static final String PREFIX_DOT = "ioc.";',
                                    'private static final String PREFIX_DOT = "collector.";')
            path = work / (name + '.java')
            path.write_text(text)
            sources.append(str(path))
            identity[name] = hashlib.sha256(original.encode()).hexdigest()
        cp = os.pathsep.join(map(str, jars))
        subprocess.run([str(jdk / 'javac'), '-proc:none', '-parameters', '--release', '21',
                        '-cp', cp, '-d', str(work), *sources,
                        str(HERE / 'ConfigurationContractProbe.java')], check=True, timeout=60)
        result = subprocess.run([str(jdk / 'java'), '-cp', str(work) + os.pathsep + cp,
                                 'com.iocextractor.bootstrap.ConfigurationContractProbe'],
                                check=True, capture_output=True, text=True, timeout=60)
        rows = result.stdout.splitlines()
        output = ROOT / 'target/configuration-c0'
        output.mkdir(parents=True, exist_ok=True)
        (output / 'results.tsv').write_text(result.stdout)
        (output / 'stderr.log').write_text(result.stderr)
        (output / 'identity.json').write_text(json.dumps({
            'head': subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip(),
            'source_sha256': identity,
            'java': subprocess.check_output([str(jdk / 'java'), '--version'], text=True).splitlines()[0],
            'dependencies': {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in jars},
            'rows': len(rows) - 1,
        }, indent=2) + '\n')
        expected = HERE / 'expected.tsv'
        if expected.exists():
            if expected.read_text() != result.stdout:
                raise SystemExit('Characterization drift; inspect target/configuration-c0/results.tsv')
            print(f'C0 reproduced: {len(rows) - 1} characterization rows match reviewed evidence')
        else:
            print(result.stdout, end='')
        print(f'Evidence: {output}')


if __name__ == '__main__':
    main()
