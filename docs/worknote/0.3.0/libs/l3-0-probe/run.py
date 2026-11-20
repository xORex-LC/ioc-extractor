#!/usr/bin/env python3
"""Characterize current logging algorithms with an unrelated field catalog."""
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import xml.etree.ElementTree as ET

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[4]
REPORT = ROOT / 'bootstrap/ioc-app/target/surefire-reports/TEST-com.iocextractor.bootstrap.LoggingControlEventObserverTest.xml'
OBSERVABILITY = 'platform/platform-observability/src/main/java/com/iocextractor/observability/'
SOURCES = [OBSERVABILITY + name + '.java' for name in (
    'MdcScope', 'LogValueType', 'EventOutcome', 'SensitiveLogValueSanitizer',
    'logging/LogEvent', 'logging/LogEvents', 'logging/LogValueNormalizer')]
SOURCES.append('bootstrap/ioc-app/src/main/java/com/iocextractor/bootstrap/IocEcsStructuredLogEncoder.java')
VERSIONS = {'spring-boot': '4.0.8', 'slf4j-api': '2.0.18',
            'logback-classic': '1.5.38', 'logback-core': '1.5.38',
            'junit-jupiter-api': '6.0.3', 'junit-jupiter-params': '6.0.3',
            'junit-jupiter-engine': '6.0.3', 'junit-platform-commons': '6.0.3',
            'junit-platform-engine': '6.0.3', 'assertj-core': '3.27.7'}


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    if not REPORT.is_file():
        raise SystemExit('First run: make test-one MODULE=bootstrap/ioc-app TEST=LoggingControlEventObserverTest')
    props = {p.get('name'): p.get('value') for p in ET.parse(REPORT).findall('./properties/property')}
    jdk = Path(os.environ.get('JAVA_HOME', props['java.home'])) / 'bin'
    if not (jdk / 'javac').is_file():
        raise SystemExit('A full JDK 21 is required; set JAVA_HOME accordingly')
    prefixes = tuple(name + '-' for name in VERSIONS) + (
        'spring-core-', 'spring-beans-', 'spring-context-', 'spring-expression-',
        'spring-aop-', 'spring-jcl-', 'commons-logging-', 'jspecify-', 'opentest4j-', 'apiguardian-api-',
        'jackson-databind-2.', 'jackson-core-2.', 'jackson-annotations-2.')
    jars = [Path(p) for p in props['java.class.path'].split(os.pathsep)
            if Path(p).suffix == '.jar' and Path(p).name.startswith(prefixes)]
    # Do not accidentally include Boot starters/test modules or any reactor output.
    jars = [p for p in jars if not p.name.startswith('spring-boot-')
            or p.name == 'spring-boot-4.0.8.jar']
    for artifact, version in VERSIONS.items():
        if sum(p.name == f'{artifact}-{version}.jar' for p in jars) != 1:
            raise SystemExit(f'Expected {artifact}:{version}; requalify a changed baseline')
    engine = next(p for p in jars if p.name == 'junit-platform-engine-6.0.3.jar')
    launcher = engine.parents[2] / 'junit-platform-launcher/6.0.3/junit-platform-launcher-6.0.3.jar'
    jars.append(launcher)
    if any(not p.is_file() for p in jars):
        raise SystemExit('Missing cached dependencies; refresh the focused test run')
    output = ROOT / 'target/logging-l3-0'
    output.mkdir(parents=True, exist_ok=True)
    source_hashes = {name: sha(ROOT / name) for name in SOURCES}
    baseline_file = HERE / 'baseline.json'
    dependencies = {p.name: sha(p) for p in jars}
    baseline = json.loads(baseline_file.read_text())
    if source_hashes != baseline['sources'] or dependencies != baseline['dependencies']:
        raise SystemExit('Source/dependency drift: review before changing baseline.json')
    with tempfile.TemporaryDirectory(prefix='logging-l3-0-') as directory:
        work = Path(directory)
        adapted = []
        for name in SOURCES:
            original = ROOT / name
            content = original.read_text()
            if name.startswith(OBSERVABILITY):
                content = re.sub(r'^package [^;]+;', 'package qualification.logging;', content, count=1)
                content = re.sub(r'^import com\.iocextractor\.observability\.[^;]+;\n', '', content, flags=re.M)
                content = re.sub(r'\bLogField\b', 'ProbeField', content)
            target = work / original.name
            target.write_text(content)
            adapted.append(target)
        cp = os.pathsep.join(map(str, jars))
        sources = adapted + sorted(HERE.glob('*.java'))
        subprocess.run([str(jdk / 'javac'), '-proc:none', '--release', '21', '-cp', cp,
                        '-d', str(work), *map(str, sources)], check=True, timeout=60)
        result = subprocess.run([str(jdk / 'java'), '-cp', str(work) + os.pathsep + cp,
                                 'qualification.logging.QualificationMain', str(baseline['expected_tests'])],
                                capture_output=True, text=True, timeout=60)
        (output / 'tests.log').write_text(result.stdout + result.stderr)
        identity = {'head': subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT,
                                                    text=True).strip(),
                    'java': subprocess.check_output([str(jdk / 'java'), '--version'],
                                                    text=True).splitlines()[0],
                    'sources': source_hashes, 'dependencies': dependencies,
                    'probe_sources': {p.name: sha(p) for p in sorted(HERE.glob('*.java'))}}
        (output / 'identity.json').write_text(json.dumps(identity, indent=2) + '\n')
        print(result.stdout, end='')
        if result.returncode:
            raise SystemExit(f'Qualification failed; inspect {output / "tests.log"}')
    print(f'Evidence: {output}')


if __name__ == '__main__':
    main()
