#!/usr/bin/env python3
"""C5 paired private whole-service SQLite qualification; never changes the production selector."""
import argparse
import csv
import hashlib
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
import urllib.error
import zipfile

sys.dont_write_bytecode = True
SPEC = importlib.util.spec_from_file_location('service_capacity', Path(__file__).with_name('service-capacity.py'))
CAP = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CAP)
REPO = CAP.REPO


def clock_preflight(root, duration_seconds=90):
    """Reject observed clock steps before starting a costly qualification JVM.

    Bracket UTC reads with monotonic samples to exclude read latency from the
    step estimate. This host diagnostic never replaces the runtime safe clock.
    """
    started = time.monotonic_ns()
    deadline = started + int(duration_seconds * 1e9)
    previous = None
    count = 0
    minimum_step = 0
    result = {'status': 'OBSERVING', 'requested_seconds': duration_seconds,
              'step_detection_ns': 100_000_000,
              'scope': 'Host UTC/monotonic preflight only; runtime safe-clock policy remains authoritative'}
    with (root / 'clock-preflight.jsonl').open('w') as stream:
        while True:
            before = time.monotonic_ns()
            utc = time.time_ns()
            after = time.monotonic_ns()
            value = dict(monotonic_before_ns=before, utc_epoch_ns=utc, monotonic_after_ns=after)
            step = 0 if previous is None else (utc - before) - (previous['utc_epoch_ns'] - previous['monotonic_after_ns'])
            value['relative_step_upper_ns'] = step
            minimum_step = min(minimum_step, step)
            count += 1
            stream.write(json.dumps(value) + '\n')
            if step < -result['step_detection_ns']:
                result['status'] = 'UNSTABLE'
                break
            if after >= deadline:
                result['status'] = 'OBSERVED_STABLE'
                break
            previous = value
            time.sleep(min(.1, (deadline - after) / 1e9))
    result.update(samples=count, elapsed_seconds=(after - started) / 1e9,
                  minimum_relative_step_upper_ns=minimum_step,
                  raw_sha256=CAP.BASE.digest(root / 'clock-preflight.jsonl'))
    CAP.save(root / 'clock-preflight.json', result)
    return result


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
        try:
            with opener.open(request, timeout=40) as response:
                return json.load(response)
        except urllib.error.HTTPError as failure:
            # Preserve bounded test-only diagnostic context, never turn an HTTP failure into a sample.
            with failure:
                body = failure.read(65537)
            if len(body) > 65536:
                raise RuntimeError('Qualification HTTP diagnostic exceeds 64 KiB') from failure
            raise RuntimeError(f'Qualification HTTP {failure.code}: {body.decode("utf-8", errors="replace")}') from failure

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
            with (self.root / 'controls.jsonl').open('a') as stream:
                stream.write(json.dumps(dict(kind='failure', monotonic=time.monotonic(),
                                             utc_epoch_ns=time.time_ns(), error=str(failure))) + '\n')

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
            return {'status': 'INCOMPLETE', 'failure': str(failure), 'control_probes': self.control_count,
                    'worker_failure_type': type(self.failure).__name__ if self.failure else None,
                    'worker_failure': str(self.failure) if self.failure else None}
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


def native_cost_evidence(root, config):
    """Compare deterministic identities and receipt payloads; exclude UTC and private occurrence IDs."""
    result = canonical_evidence(root, config)
    queries = {
        'aliases': 'SELECT artifact,definition_id,key_hash,key_canonical,lifecycle_id,canonical_row_id FROM canonical_match_alias',
        'field_origins': 'SELECT artifact,lifecycle_id,field_name,admission_order,occurrence_position FROM canonical_lifecycle_field_origin',
        'receipt_headers': 'SELECT state,expected_artifacts,row_count,payload_version FROM confirmation_receipt',
        'receipt_positions': 'SELECT artifact,ordinal,field_name,occurrence_position FROM confirmation_receipt_field_position',
        'observation_order': 'SELECT admission_order,origin_kind FROM registered_observation',
    }
    for artifact in CAP.BASE.ARTIFACTS:
        queries[artifact + '_identities'] = f'SELECT row_key,id,_lifecycle_id FROM {artifact}'
        schema = next(item for item in config['ioc']['sink']['artifacts'] if item['name'] == artifact)
        columns = ','.join('"' + column['name'].replace('"', '""') + '"'
                           for column in schema['columns'] if column['name'] != 'id')
        queries[artifact + '_receipt_payload'] = f'SELECT ordinal,row_key,{columns} FROM {artifact}_receipt_rows'
    fingerprints = {}
    with closing(sqlite3.connect(f'file:{root / "var/db/ioc-dataframe.db"}?mode=ro', uri=True)) as db:
        db.execute('BEGIN')
        for name, sql in queries.items():
            cursor = db.execute(sql + ' LIMIT 0')
            order = ','.join(str(index + 1) for index in range(len(cursor.description)))
            digest = hashlib.sha256()
            count = 0
            for row in db.execute(sql + ' ORDER BY ' + order):
                digest.update(json.dumps(row, ensure_ascii=False, separators=(',', ':')).encode() + b'\n')
                count += 1
            fingerprints[name] = {'rows': count, 'sha256': digest.hexdigest()}
    result['deterministic_fingerprints'] = fingerprints
    result['fingerprint_scope'] = 'Final key/ID/lifecycle assignments, aliases, field rank, receipt payload/order; excludes UTC and private occurrence IDs'
    return result


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


def series_screen(samples, rows, requested_pairs):
    """Keep incomplete/failed series negative and enforce the unchanged median budget."""
    summaries = {}
    violations = []
    budget = 180 if rows >= 1000000 else 30
    for backend in ('native', 'staged'):
        members = [item for item in samples if item['backend'] == backend]
        values = [item['local_window_upper_seconds'] for item in members
                  if item['status'] in ('SCREEN_PASS', 'FAIL') and item.get('local_window_upper_seconds') is not None]
        if len(members) != requested_pairs or any(item['status'] != 'SCREEN_PASS' for item in members):
            violations.append(backend + '_incomplete_or_failed_series')
        summaries[backend] = {'completed_windows': len(values), 'requested_pairs': requested_pairs,
                              'median_upper_seconds': statistics.median(values) if values else None,
                              'range_upper_seconds': [min(values), max(values)] if values else None}
        if len(values) == requested_pairs and statistics.median(values) > budget:
            violations.append(backend + '_median_local_window')
    return {'status': 'FAIL' if violations else 'SCREEN_PASS', 'median_budget_seconds': budget,
            'violations': violations, 'backends': summaries}


def native_cost_series(samples, requested):
    """A fixed diagnostic series can complete with failed screens; it never accepts capacity."""
    complete = [item for item in samples if item.get('backend') == 'native'
                and item.get('status') in ('SCREEN_PASS', 'FAIL')
                and item.get('qualification', {}).get('admissionWindowMaximaExact') is True
                and item.get('qualification', {}).get('admissionScope') == 'canonical_bean_only'
                and item.get('canonical_oracle') and item.get('canonical_evidence')
                and item.get('local_window_upper_seconds') is not None
                and not item.get('cleanup_errors') and item.get('temporary_state_removed')]
    return {'status': 'COMPLETE' if len(complete) == len(samples) == requested else 'INCOMPLETE',
            'completed_windows': len(complete), 'requested_windows': requested,
            'screen_statuses': [item['status'] for item in samples],
            'acceptance': 'NOT_ACCEPTED; completed cost measurements do not pass C5/G6'}


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
    parser.add_argument('--clock-preflight-only', action='store_true',
                        help='Record the 90-second host UTC check without launching a service')
    parser.add_argument('--native-cost-cycle', action='store_true',
                        help='Fixed native-only cost series; retain failed screens, stop incomplete collection; no acceptance')
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
              'native_cost_cycle': args.native_cost_cycle,
              'bounded_row_profile': args.bounded_row_profile,
              'mode': 'diagnostic' if args.diagnostic else 'instrumented_primary',
              'requested_pairs': 0 if args.native_cost_cycle else args.samples,
              'requested_native_windows': args.samples if args.native_cost_cycle else None,
              'acceptance': 'NOT_ACCEPTED; C5 qualification does not activate ADR0041 or close G6', 'samples': []}
    args.smb, args.environment, args.document = False, None, None
    args.qualification = lambda root, port: QualificationWindow(root, port, args.maintenance_every)
    args.gate_sample = gates
    args.canonical_evidence = native_cost_evidence if args.native_cost_cycle else canonical_evidence
    CAP.save(output / 'report.json', report)
    report['clock_preflight'] = clock_preflight(output)
    if report['clock_preflight']['status'] != 'OBSERVED_STABLE':
        report.update(status='BLOCKED_CLOCK', failure='Host UTC stepped backwards; no qualification JVM started')
        CAP.save(output / 'report.json', report)
        raise SystemExit(1)
    if args.clock_preflight_only:
        report['status'] = 'PREFLIGHT_ONLY'
        CAP.save(output / 'report.json', report)
        return
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
            backends = ('native',) if args.native_cost_cycle else (
                ('native', 'staged') if ordinal % 2 == 0 else ('staged', 'native'))
            for backend in backends:
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
            if args.native_cost_cycle:
                if native_cost_series(report['samples'], ordinal + 1)['status'] != 'COMPLETE':
                    report['stopped_after_incomplete_window'] = True
                    break
                continue
            try:
                report['comparison'] = compare(report['samples'])
            except Exception as failure:
                report['comparison_failure'] = str(failure)
                break
            if len(report['samples']) != 2 * (ordinal + 1) or any(item['status'] != 'SCREEN_PASS' for item in report['samples'][-2:]):
                report['stopped_after_failed_pair'] = True
                break
    report['temporary_runtime_removed'] = not runtime.exists()
    if args.native_cost_cycle:
        report['cost_series'] = native_cost_series(report['samples'], args.samples)
        report['status'] = report['cost_series']['status']
    else:
        report['series_screen'] = series_screen(report['samples'], args.rows, args.samples)
        report['status'] = report['series_screen']['status']
    if report.get('comparison_failure'):
        report['status'] = 'ERROR'
    if CAP.command(['git', 'rev-parse', 'HEAD']) != head or CAP.command(['git', 'status', '--porcelain']):
        report.update(status='ERROR', identity_failure='HEAD/worktree changed during qualification')
    CAP.save(output / 'report.json', report)
    if report['status'] not in ('SCREEN_PASS', 'COMPLETE'):
        raise SystemExit(1)


if __name__ == '__main__':
    main()
