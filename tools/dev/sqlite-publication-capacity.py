#!/usr/bin/env python3
"""C5 paired private whole-service SQLite qualification; never changes the production selector."""
import argparse
import csv
from contextlib import closing
import importlib.util
import json
from pathlib import Path
import shutil
import sqlite3
import statistics
import sys
import tempfile
import threading
import time
import urllib.request
import zipfile

sys.dont_write_bytecode = True
SPEC = importlib.util.spec_from_file_location('service_capacity', Path(__file__).with_name('service-capacity.py'))
CAP = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CAP)
REPO = CAP.REPO


class QualificationWindow:
    """Bounded control/cleanup load and fail-closed, exact admission/allocation measurement."""
    def __init__(self, root, port, maintenance_every=5):
        self.root, self.port = root, port
        self.maintenance_every = maintenance_every
        self.stop = threading.Event()
        self.thread = None
        self.failure = None
        self.result = None
        self.control_count = 0
        self.maximum_control_seconds = 0
        self.maximum_lateness_seconds = 0

    def request(self, path):
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
        request = urllib.request.Request(f'http://127.0.0.1:{self.port}/qualification/' + path,
                                         data=b'', method='POST')
        with opener.open(request, timeout=40) as response:
            return json.load(response)

    def begin(self):
        self.request('window/start')
        self.thread = threading.Thread(target=self.run, name='c5-controls', daemon=False)
        self.thread.start()

    def run(self):
        try:
            with (self.root / 'controls.jsonl').open('w') as stream:
                due = time.monotonic()
                while not self.stop.is_set():
                    invoked = time.monotonic()
                    value = self.request('control')
                    completed = time.monotonic()
                    self.control_count += 1
                    self.maximum_control_seconds = max(self.maximum_control_seconds,
                                                        value['eligibleToCompleteNanos'] / 1e9)
                    self.maximum_lateness_seconds = max(self.maximum_lateness_seconds, invoked - due)
                    stream.write(json.dumps(dict(kind='control', due_monotonic=due,
                                                 invoked_monotonic=invoked, completed_monotonic=completed, **value)) + '\n')
                    if self.maintenance_every and self.control_count % self.maintenance_every == 0:
                        value = self.request('maintenance')
                        stream.write(json.dumps(dict(kind='maintenance', completed_monotonic=time.monotonic(), **value)) + '\n')
                    stream.flush()
                    due = completed + 1
                    self.stop.wait(max(0, due - time.monotonic()))
        except BaseException as failure:
            self.failure = failure

    def close(self):
        self.stop.set()
        if self.thread:
            self.thread.join(45)
            if self.thread.is_alive():
                raise RuntimeError('Qualification control worker did not terminate')
        if self.failure:
            raise RuntimeError('Qualification control worker failed') from self.failure

    def finish(self):
        self.close()
        self.result = self.request('window/end')
        self.result.update(control_probes=self.control_count,
                           maximum_eligible_control_seconds=self.maximum_control_seconds,
                           maximum_control_scheduled_lateness_seconds=self.maximum_lateness_seconds)
        if self.control_count == 0:
            raise RuntimeError('No concurrent control probes completed')
        return self.result

    def failure_summary(self):
        # Preserve partial facts as failure evidence, never as a successful window.
        try:
            value = self.finish()
        except Exception as failure:
            return {'status': 'INCOMPLETE', 'failure': str(failure), 'control_probes': self.control_count}
        return dict(status='INCOMPLETE_WORKLOAD', **value)


def gates(sample, size):
    value = CAP.gate_sample(sample, size)
    # Replace the cumulative-health upper bound only with a successful exact window.
    exact = sample['qualification']['writerOperations']
    if sample['qualification'].get('admissionScope') != 'canonical_bean_only':
        raise RuntimeError('Missing or incompatible canonical admission scope')
    violations = [item for item in value['violations'] if not item.startswith(('writer_hold', 'control_wait'))]
    if not exact:
        raise RuntimeError('No measured writer admissions')
    for item in exact.values():
        if item['maximumHoldNanos'] > 5e9:
            violations.append('writer_hold')
            break
    if exact.get('CONTROL', {}).get('maximumWaitNanos', 0) > 10e9:
        violations.append('control_wait')
    value.update(violations=violations, writer_window=exact,
                 status='FAIL' if violations else 'SCREEN_PASS', admission_window_maxima_exact=True)
    return value


def canonical_evidence(root, config):
    """Record receipt/history/slot/control families in addition to the existing independent row oracle."""
    database = root / 'var/db/ioc-dataframe.db'
    with closing(sqlite3.connect(f'file:{database}?mode=ro', uri=True)) as db:
        db.execute('BEGIN')
        names = [row[0] for row in db.execute("SELECT name FROM sqlite_master WHERE type IN ('table','view') "
                                            "AND name NOT LIKE 'sqlite_%' ORDER BY name")]
        counts = {name: db.execute('SELECT count(*) FROM "' + name.replace('"', '""') + '"').fetchone()[0]
                  for name in names}
        orphan_origins = {}
        for artifact in CAP.BASE.ARTIFACTS:
            orphan_origins[artifact] = db.execute(f'SELECT count(*) FROM {artifact}_sources s LEFT JOIN '
                                                 f'{artifact} c ON c.id=s.row_id WHERE c.id IS NULL').fetchone()[0]
        if any(orphan_origins.values()):
            raise RuntimeError('Committed provenance contains orphan canonical identities')
        return {'family_rows': counts, 'orphan_origins': orphan_origins,
                'scope': 'Committed-family census; independent fields/keys/provenance and public slots checked separately'}


def import_policy(policy, artifact, processed=False):
    """Declare one real local contract using the existing schema/identity/export catalogs."""
    ioc = policy['ioc']
    identity = next(item for item in ioc['artifact-identity']['artifacts'] if item['name'] == artifact)
    schema = next(item for item in ioc['sink']['artifacts'] if item['name'] == artifact)
    columns = [item['name'] for item in schema['columns'] if item['name'] != 'id']
    has_id = any(item['name'] == 'id' for item in schema['columns'])
    contract_id = 'capacity-' + artifact + '-v1'
    mapping = {'name': artifact, 'role': 'primary', 'record-key': identity['record-key'],
               'match-keys': [item['name'] for item in identity['match-keys']],
               'columns': [{'target': name, 'source': name} for name in columns]}
    if 'source' in columns:
        mapping['source-label-target'] = 'source'
    contract = {'id': contract_id, 'version': 1, 'charset': 'UTF-8',
                'dialect': {'delimiter': ';', 'quote': '"', 'record-separator': 'crlf-or-lf',
                            'header-required': True, 'null-literals': ['NULL']},
                'recognition': {'required-columns': (['id'] if has_id else []) + columns},
                'mode': 'as-is', 'routing': 'target-only', 'row-failure-policy': 'accept-valid',
                'duplicate-policy': 'keep-first', 'renew-unchanged': True, 'formula-policy': 'reject',
                'merge-default': 'fill-missing', 'artifacts': [mapping]}
    if has_id:
        profile = next(item['name'] for item in ioc['export']['profiles'] if artifact in item['artifacts'])
        contract['requested-slot'] = {'source-column': 'id', 'profile': profile, 'existing-record-policy': 'preserve-existing'}
    if processed:
        if artifact != 'masks':
            raise ValueError('Processed host-collapse fixture requires masks')
        template = CAP.yaml.safe_load((REPO / 'bootstrap/ioc-app/src/test/resources/application-selected-import-production.yml').read_text())
        contract = template['ioc']['dataframe-import']['contracts'][0]
        contract['id'] = contract_id
        contract['processed-route']['plan'] = 'capacity-import-hosts'
        plan = template['ioc']['processing']['plans'][0]
        plan['name'] = 'capacity-import-hosts'
        ioc['processing']['plans'] = [item for item in ioc['processing']['plans'] if item['name'] != plan['name']] + [plan]
    ioc['dataframe-import'].update(enabled=True,
        sources=[{'id': 'trusted-local', 'transport': 'local', 'location': './var/import/inbox',
                  'contracts': [contract_id], 'authority': 'capacity-local'}],
        **{'authority-profiles': [{'id': 'capacity-local', 'artifacts': [artifact], 'maximum-merge-policy': 'fill-missing'}],
           'contracts': [contract]})


def processed_fixture(path, count, artifact, config, oracle, digest, shape):
    """Independent mass URL-to-domain oracle, with compatible COALESCE contributors."""
    keys = min(20, count) if shape == 'collapse' else count
    source = 'dataframe-import:trusted-local'
    with path.open('w', newline='') as stream:
        writer = csv.writer(stream, delimiter=';', lineterminator='\n')
        writer.writerow(['ioc', 'source'])
        for number in range(count):
            host = f'import-host-{number % keys}.example.test'
            value = f'https://{host}:9090/path/{number}?sample={number}'
            writer.writerow([value, 'Imported Source'])
            oracle.add('masks', {'mask': host, 'url_match': 'u:hAS', 'host_match': 'h:dAS',
                                 'source': 'Imported Source'}, source_key=source)
    oracle.db.commit()
    return {'artifact': artifact, 'accepted_rows': keys, 'duplicate_rows': count - keys, 'source_rows': count,
            'has_slots': False, 'sha256': digest(path), 'bytes': path.stat().st_size,
            'contract_id': 'capacity-masks-v1', 'source_key': source, 'mode': 'processed',
            'expected_final_hosts': keys, 'host_derivation': True}


def freeze_runtime(jar, target):
    """Freeze the packaged application plus only explicitly selected test-only helpers."""
    with zipfile.ZipFile(jar) as archive:
        for name in archive.namelist():
            application_metadata = name.startswith('META-INF/') and not name.startswith(
                ('META-INF/services/', 'META-INF/BOOT.', 'META-INF/MANIFEST.'))
            if (name.startswith(('BOOT-INF/classes/', 'BOOT-INF/lib/')) or application_metadata) and not name.endswith('/'):
                # Boot lifts application META-INF (including preflight/environment factories)
                # to the JAR root. Preserve those resources in the ordinary classpath.
                relative = Path('classes') / name if application_metadata else Path(name).relative_to('BOOT-INF')
                if '..' in relative.parts:
                    raise ValueError('Unsafe packaged runtime path')
                path = target / relative
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(archive.read(name))
    helpers = REPO / 'bootstrap/ioc-app/target/test-classes'
    classes = []
    for package, prefix in [('com/iocextractor/bootstrap', 'SqliteCapacityApplication'),
                            ('com/iocextractor/bootstrap', 'SqliteCapacityMetrics'),
                            ('com/iocextractor/adapter/out/store/jdbc', 'StagedCapacityBundle')]:
        matched = sorted((helpers / package).glob(prefix + '*.class'))
        if not matched:
            raise ValueError('Compile the qualification helpers first: ' + prefix)
        for source in matched:
            path = target / 'classes' / source.relative_to(helpers)
            path.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(source, path)
            classes.append(path)
    agent = target / 'admission-agent.jar'
    with zipfile.ZipFile(agent, 'w') as archive:
        archive.writestr('META-INF/MANIFEST.MF', 'Manifest-Version: 1.0\nPremain-Class: com.iocextractor.bootstrap.SqliteCapacityMetrics\n\n')
        for path in classes:
            if path.name.startswith('SqliteCapacityMetrics'):
                archive.write(path, path.relative_to(target / 'classes'))
    return str(target / 'classes') + ':' + str(target / 'lib/*'), agent


def compare(samples):
    pairs = []
    for ordinal in sorted({item['pair'] for item in samples}):
        pair = {item['backend']: item for item in samples if item['pair'] == ordinal}
        if pair.keys() != {'native', 'staged'}:
            pairs.append({'pair': ordinal, 'status': 'INCOMPLETE'})
            continue
        native, staged = pair['native'], pair['staged']
        if native.get('canonical_oracle') and staged.get('canonical_oracle'):
            if native['canonical_oracle'] != staged['canonical_oracle'] or native['input']['sha256'] != staged['input']['sha256']:
                raise RuntimeError('Paired inputs or independent canonical output/provenance differ')
            pairs.append({'pair': ordinal, 'semantics': 'PASS; identical independent final fields/keys/provenance',
                          'native_status': native['status'], 'staged_status': staged['status'],
                          'staged_native_time_ratio': staged['local_window_upper_seconds'] / native['local_window_upper_seconds'],
                          'staged_native_allocations_ratio': staged['qualification']['allocatedProcessBytes'] /
                                                            native['qualification']['allocatedProcessBytes']})
        else:
            pairs.append({'pair': ordinal, 'status': 'INCOMPLETE',
                          'native_status': native['status'], 'staged_status': staged['status']})
    return pairs


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jar', type=Path, required=True)
    parser.add_argument('--config', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--rows', type=int, default=100000)
    parser.add_argument('--initial-rows', type=int, default=0)
    parser.add_argument('--samples', type=int, default=3, help='Paired forks; stop new pairs after a failed screen')
    parser.add_argument('--format', choices=['html', 'docx'], default='html')
    parser.add_argument('--shape', choices=['mostly-unique', 'unique', 'collapse'], default='mostly-unique')
    parser.add_argument('--import-artifact', choices=CAP.BASE.ARTIFACTS)
    parser.add_argument('--processed-import', action='store_true', help='Actual selected masks import with active host view and COALESCE')
    parser.add_argument('--diagnostic', action='store_true')
    parser.add_argument('--timeout', type=int, default=900)
    parser.add_argument('--port', type=int, default=18207)
    parser.add_argument('--maintenance-every', type=int, default=5,
                        help='Extra retention/version page every N control probes; 0 keeps only normal service cleanup cadence')
    parser.add_argument('--bounded-row-profile', action='store_true',
                        help='Explicit common 16 KiB row / 4 KiB field / 1 MiB cache profile; default-limit admission stays open')
    args = parser.parse_args()
    output = args.output.absolute()
    if not output.is_relative_to(REPO / '.dev') or output.exists() or output.resolve() != output:
        parser.error('Use a new private repo-local .dev directory without symlinks')
    if min(args.rows, args.samples, args.timeout) < 1 or args.initial_rows < 0 or args.maintenance_every < 0:
        parser.error('Positive workload, sample and timeout values required')
    if args.processed_import and args.import_artifact != 'masks':
        parser.error('--processed-import requires --import-artifact masks')
    if CAP.command(['git', 'status', '--porcelain']):
        parser.error('Commit the harness and policy before measurement')
    head = CAP.command(['git', 'rev-parse', 'HEAD'])
    output.mkdir(parents=True)
    report = {'source_commit': head, 'jar_sha256': CAP.BASE.digest(args.jar), 'policy_sha256': CAP.BASE.digest(args.config),
              'driver_sha256': CAP.BASE.digest(__file__), 'service_driver_sha256': CAP.BASE.digest(CAP.__file__),
              'collector_schema': 2, 'admission_scope': 'canonical_bean_only',
              'extra_maintenance_every_control_probes': args.maintenance_every,
              'java': CAP.command(['java', '--version']), 'rows': args.rows, 'shape': args.shape, 'format': args.format,
              'initial_rows': args.initial_rows, 'import_artifact': args.import_artifact,
              'processed_import': args.processed_import,
              'bounded_row_profile': args.bounded_row_profile,
              'mode': 'diagnostic' if args.diagnostic else 'instrumented_primary', 'requested_pairs': args.samples,
              'acceptance': 'NOT_ACCEPTED; C5 qualification does not activate ADR0041 or close G6', 'samples': []}
    args.smb, args.environment, args.document = False, None, None
    args.qualification = lambda root, port: QualificationWindow(root, port, args.maintenance_every)
    args.gate_sample, args.canonical_evidence = gates, canonical_evidence
    CAP.save(output / 'report.json', report)
    with tempfile.TemporaryDirectory(prefix='ioc-c5-runtime-', dir=REPO / '.dev') as temporary:
        runtime = Path(temporary)
        frozen = runtime / 'ioc-app.jar'
        shutil.copyfile(args.jar, frozen)
        if args.bounded_row_profile or args.import_artifact:
            policy = CAP.yaml.safe_load(args.config.read_text())
            if args.bounded_row_profile:
                policy['ioc'].setdefault('processing', {}).setdefault('workspace', {}).update(
                    **{'maximum-row-bytes': 16384, 'maximum-field-bytes': 4096, 'cache-kib': 1024})
            if args.import_artifact:
                import_policy(policy, args.import_artifact, args.processed_import)
            args.config = runtime / 'common-policy.yml'
            args.config.write_text(CAP.yaml.safe_dump(policy, sort_keys=False, allow_unicode=True))
            report['common_effective_policy_sha256'] = CAP.BASE.digest(args.config)
        if args.processed_import:
            args.import_fixture = lambda *values: processed_fixture(*values, args.shape)
        cp, agent = freeze_runtime(frozen, runtime)
        report['helper_class_sha256'] = {str(path.relative_to(runtime)): CAP.BASE.digest(path)
                                         for path in sorted((runtime / 'classes').rglob('*Capacity*.class'))}
        for ordinal in range(args.samples):
            # Alternate paired fork order; both variants run even when one fails its screen.
            for backend in (('native', 'staged') if ordinal % 2 == 0 else ('staged', 'native')):
                if shutil.disk_usage(REPO / '.dev').free < 5 * 1024**3:
                    report['failure'] = 'Less than 5 GiB free; no new JVM started'
                    break
                args.launcher = ['-Dqualification.sqlite=' + backend, '-javaagent:' + str(agent), '-cp', cp,
                                 'com.iocextractor.bootstrap.SqliteCapacityApplication']
                try:
                    value = CAP.sample(args, frozen, report, len(report['samples']), output)
                except KeyboardInterrupt:
                    report.update(status='ABORTED', interruption='Caller interrupted; owned service state cleaned')
                    report['samples'][-1].update(backend=backend, pair=ordinal)
                    CAP.save(output / 'report.json', report)
                    raise
                value.update(backend=backend, pair=ordinal)
                CAP.save(output / 'report.json', report)
            try:
                report['comparison'] = compare(report['samples'])
            except Exception as failure:
                report['comparison_failure'] = str(failure)
                break
            if len(report['samples']) != 2 * (ordinal + 1) or any(item['status'] != 'SCREEN_PASS' for item in report['samples'][-2:]):
                report['stopped_after_failed_pair'] = True
                break
    report['temporary_runtime_removed'] = not runtime.exists()
    report['status'] = 'SCREEN_PASS' if len(report['samples']) == args.samples * 2 and all(
        item['status'] == 'SCREEN_PASS' for item in report['samples']) else 'FAIL'
    if report.get('comparison_failure'):
        report['status'] = 'ERROR'
    if report['status'] == 'SCREEN_PASS':
        report['medians'] = {backend: statistics.median(item['local_window_upper_seconds'] for item in report['samples']
                                                        if item['backend'] == backend) for backend in ('native', 'staged')}
    if CAP.command(['git', 'rev-parse', 'HEAD']) != head or CAP.command(['git', 'status', '--porcelain']):
        report.update(status='ERROR', identity_failure='HEAD/worktree changed during qualification')
    CAP.save(output / 'report.json', report)
    if report['status'] != 'SCREEN_PASS':
        raise SystemExit(1)


if __name__ == '__main__':
    main()
