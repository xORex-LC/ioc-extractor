#!/usr/bin/env python3
"""CAP-0/1 qualification with frozen runtimes, private state and independent oracles."""
import argparse
import csv
from contextlib import closing, ExitStack
import gzip
import hashlib
import importlib.util
import ipaddress
import json
import os
from pathlib import Path
import re
import shlex
import sqlite3
import subprocess
import sys
import tempfile
import threading
import time
import urllib.request
from urllib.parse import urlsplit
import uuid
import shutil
import statistics
import zipfile
from html.parser import HTMLParser
import yaml

sys.dont_write_bytecode = True
REPO = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location('capacity_comparison', Path(__file__).with_name('processing-optimization-comparison.py'))
COMPARISON = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(COMPARISON)
ARTIFACTS = ('masks', 'ip_list', 'hashes', 'address_blacklist', 'ioc_aggregate')


def digest(path):
    with Path(path).open('rb') as contents:
        return hashlib.file_digest(contents, 'sha256').hexdigest()


def rows(database, sql, parameters=()):
    with closing(sqlite3.connect(f'file:{database}?mode=ro', uri=True)) as connection:
        connection.row_factory = sqlite3.Row
        return [dict(row) for row in connection.execute(sql, parameters)]


def write_report(root, name, value):
    (root / name).write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n')


def snapshot_state(dataframe, service, target, retain=False):
    """Record state facts; optionally retain one compressed coherent SQLite backup."""
    target.mkdir()
    result = {}
    for name, source in (('dataframe', dataframe), ('service', service)):
        path = target / (name + '.db')
        with ExitStack() as owners:
            original = owners.enter_context(closing(sqlite3.connect(f'file:{source}?mode=ro', uri=True)))
            if retain:
                with closing(sqlite3.connect(path)) as backup:
                    original.backup(backup)
                snapshot = owners.enter_context(closing(sqlite3.connect(path)))
            else:
                snapshot = original
            snapshot.execute('BEGIN')
            if snapshot.execute('PRAGMA integrity_check').fetchone()[0] != 'ok':
                raise RuntimeError('Invalid private state backup: ' + name)
            tables = {row[0] for row in snapshot.execute("SELECT name FROM sqlite_master WHERE type='table'")}
            facts = {'schema_version': snapshot.execute('PRAGMA user_version').fetchone()[0]}
            if name == 'dataframe':
                facts['artifact_rows'] = {artifact: snapshot.execute(f'SELECT COUNT(*) FROM {artifact}').fetchone()[0]
                                          for artifact in ARTIFACTS}
                facts['alias_rows'] = snapshot.execute('SELECT COUNT(*) FROM canonical_match_alias').fetchone()[0]
                facts['revisions'] = snapshot.execute('SELECT artifact,revision FROM artifact_revision ORDER BY artifact').fetchall()
            else:
                facts['coordination_rows'] = {table: snapshot.execute(f'SELECT COUNT(*) FROM {table}').fetchone()[0]
                                             for table in ('ingest_run', 'import_delivery', 'publish_ledger') if table in tables}
            snapshot.rollback()
        facts['state_retained'] = retain
        if retain:
            facts['sha256'] = digest(path)
            archive = path.with_suffix('.db.gz')
            with path.open('rb') as contents, gzip.open(archive, 'wb', compresslevel=1) as output:
                shutil.copyfileobj(contents, output)
            path.unlink()
            facts.update(file=str(archive), compressed_sha256=digest(archive), compressed_bytes=archive.stat().st_size)
        result[name] = facts
    write_report(target, 'manifest.json', result)
    return result


def phases(args, root, report):
    classpath, identity = COMPARISON.frozen_runtime(args.runtime.resolve())
    report.update(runtime=identity, mode='diagnostic-instrumentation-and-jfr', agent_sha256=digest(args.agent),
                  rows=args.rows, pairs=args.pairs, initial_state='fresh empty stores in each fork',
                  primary_flags=['-Xms128m', '-Xmx512m'], samples=[])
    route = COMPARISON.COMPARISON
    os.environ.update(DEBUG='false', TRACE='false', LOGGING_LEVEL_ROOT='WARN')
    fixtures = route.fixtures(root, args.rows, args.rows, args.rows, args.rows, 'mixed')
    shutil.copyfile(args.agent, root / 'comparison-diagnostics.jar')
    signature_checksum = None
    for iteration in range(args.pairs):
        metrics, signature = route.run_one(root, fixtures['document'], 'document', True, iteration,
            classpath, args.rows, args.rows, 0, args.runtime.resolve() / 'test-resources', True, 'mixed',
            capacity=True, retain_state=args.retain_state)
        if signature_checksum is not None and signature_checksum != metrics['signature_sha256']:
            raise RuntimeError('Diagnostic fork semantics differ')
        signature_checksum = metrics['signature_sha256']
        report['final_rows'] = {artifact: len(keys) for artifact, keys in signature['canonical_keys'].items()}
        del signature
        report['samples'].append(metrics)
        write_report(root, 'partial.json', report)
        print(f'Diagnostic document {args.rows}: {iteration + 1}/{args.pairs}', flush=True)
    report['canonical_promotion_median_nanos'] = statistics.median(
        value['diagnostic_counters']['canonical_writer_hold_nanos'] for value in report['samples'])
    report['scope'] = 'Instrumentation/JFR phase evidence; not a primary wall-time or resource-budget sample.'
    if identity != COMPARISON.frozen_runtime(args.runtime.resolve())[1]:
        raise RuntimeError('Frozen runtime changed during qualification')


def sql_screen(args, root, report):
    classpath, identity = COMPARISON.frozen_runtime(args.runtime.resolve())
    report['runtime'] = identity
    source = REPO / 'tools/dev/JdbcMatcherCapacityProbe.java'
    report['probe_sha256'] = digest(source)
    classes = root / 'classes'
    classes.mkdir()
    subprocess.run(['java', '--add-modules', 'jdk.compiler', 'com.sun.tools.javac.Main',
                    '-cp', classpath, '-d', str(classes), str(source)], check=True,
                   capture_output=True, text=True, timeout=60)
    measurements = []
    for size in (1000, 10000, 100000):
        command = ['java', '-Xms128m', '-Xmx512m', '-cp', str(classes) + ':' + classpath,
                   'com.iocextractor.adapter.out.store.jdbc.JdbcMatcherCapacityProbe', str(size)]
        if args.database:
            command.append(str(args.database.resolve()))
        result = subprocess.run(command,
                                capture_output=True, text=True, timeout=120)
        (root / f'sql-{size}.log').write_text(result.stdout + result.stderr)
        result.check_returncode()
        measurements.extend(json.loads(line) for line in result.stdout.splitlines() if line.startswith('{'))
    for shape in ('singleton-hit', 'singleton-miss', 'multi-key', 'multi-request'):
        work = {item['aliases']: item['vmCallbacksQuantum1'] for item in measurements if item.get('shape') == shape}
        if set(work) != {1000, 10000, 100000} or work[100000] > 2 * work[1000] + 5000:
            raise RuntimeError(f'Artifact-size work multiplier: {shape}: {work}')
    report.update(measurements=measurements, counter_scope='Quantum 1 callbacks; actual packaged planner call including setup/cleanup. Diagnostic work, not primary latency.',
                  fixture_scope='Private minimal SQL mechanism fixture; business states use public application paths.')
    if args.database:
        report['public_application_state'] = {'sha256': digest(args.database),
            'schema_version': rows(args.database, 'PRAGMA user_version')[0]['user_version'],
            'alias_rows': rows(args.database, 'SELECT COUNT(*) AS total FROM canonical_match_alias')[0]['total'],
            'scope': 'Plan-only cross-check on a read-only public-path state; VM counters belong to the mechanism fixture.'}


class FixtureOracle(HTMLParser):
    """Independent fixture reader, deliberately limited to the pinned six-type HTML corpus."""
    def __init__(self, config, document_source_key=None):
        super().__init__(convert_charrefs=True)
        self.config = config
        self.tag = None
        self.text = []
        self.source = None
        self.document_source_key = document_source_key
        self.observations = 0
        self.expected = {artifact: {} for artifact in ARTIFACTS}
        self.origins = {artifact: {} for artifact in ARTIFACTS}
        self.columns = {item['name']: [column['name'] for column in item['columns'] if column['name'] != 'id']
                        for item in config['ioc']['sink']['artifacts']}
        self.keys = {item['name']: item['key-columns'] for item in config['ioc']['artifact-identity']['artifacts']}

    def handle_starttag(self, tag, attributes):
        if tag in ('h1', 'h2', 'p'):
            self.tag, self.text = tag, []

    def handle_data(self, text):
        if self.tag:
            self.text.append(text)

    def handle_endtag(self, tag):
        if tag != self.tag:
            return
        value = ''.join(self.text)
        self.tag = None
        if tag in ('h1', 'h2'):
            if not re.fullmatch(r'БИБ-\d+', value):
                raise ValueError('Oracle only supports pinned BIB section fixtures')
            self.source = value
            return
        value = re.sub(r'^sample-\d+ :: ', '', value)
        for rule in self.config['ioc']['refang']['rules']:
            value = value.replace(rule['from'], rule['to'])
        self.observations += 1
        if re.fullmatch(r'[a-fA-F0-9]{32}|[a-fA-F0-9]{40}|[a-fA-F0-9]{64}', value):
            algorithm = {32: 'hash_md5', 40: 'hash_sha1', 64: 'hash_sha256'}[len(value)]
            self.add('hashes', {algorithm: value.upper(), 'source': self.source})
            self.add('ioc_aggregate', {'hash': value.upper(), 'name': self.source}, last=True)
            return
        parsed = urlsplit(value if '://' in value else '//' + value)
        host = parsed.hostname
        if host is None:
            raise ValueError('Unrecognized oracle fixture value')
        try:
            ipaddress.IPv4Address(host)
            is_ip = True
        except ipaddress.AddressValueError:
            is_ip = False
            if not (host.endswith('.example.test') or host in ('example.org', 'sub.example.org')):
                raise ValueError('Unqualified PSL host in pinned oracle')
        if is_ip:
            self.add('ip_list', {'ip': host, 'source': self.source})
            self.add('address_blacklist', {'forbidden_ip': host})
        else:
            # .test has no public suffix in the pinned Guava PSL; example.org is registrable.
            subdomain = host == 'sub.example.org'
            self.add('masks', {'mask': host, 'url_match': 'u:hEX' if subdomain else 'u:hAS',
                              'host_match': 'h:dEX' if subdomain else 'h:dAS', 'source': self.source})
            self.add('address_blacklist', {'forbidden_url': host})
        bare = value == host
        self.add('ioc_aggregate', {'ip_address': host if is_ip else None,
                                  'url_match': None if bare else value,
                                  'host_match': None if is_ip else host, 'name': self.source}, last=True)

    def add(self, artifact, supplied, last=False, source_key=None):
        values = {column: supplied.get(column) for column in self.columns[artifact]}
        material = [None if values[column] is None else str(values[column]) for column in self.keys[artifact]]
        key = hashlib.sha256(json.dumps(material, separators=(',', ':')).encode()).hexdigest()
        if last or key not in self.expected[artifact]:
            self.expected[artifact][key] = values
            self.origins[artifact][key] = source_key or self.document_source_key or self.source

    def check_database(self, database):
        result = {}
        with sqlite3.connect(f'file:{database}?mode=ro', uri=True) as connection:
            for artifact in ARTIFACTS:
                expected = dict(self.expected[artifact])
                digest_result = hashlib.sha256()
                names = self.columns[artifact]
                count = 0
                for row in connection.execute(f'SELECT row_key,{",".join(names)} FROM {artifact} ORDER BY row_key'):
                    key, *values = row
                    actual = {name: None if value == 'NULL' or value is None else str(value)
                              for name, value in zip(names, values)}
                    wanted = expected.pop(key, None)
                    if wanted is None or actual != {name: None if value is None else str(value) for name, value in wanted.items()}:
                        raise RuntimeError(f'Independent oracle: unexpected fields/key in {artifact}: {key}')
                    digest_result.update(json.dumps([key, actual], ensure_ascii=False, sort_keys=True).encode() + b'\n')
                    count += 1
                if expected:
                    raise RuntimeError(f'Independent oracle: missing {len(expected)} rows in {artifact}')
                provenance = connection.execute(f'SELECT c.row_key,s.source_key,s.occurrences FROM {artifact} c '
                    f'JOIN {artifact}_sources s ON s.row_id=c.id').fetchall()
                if len(provenance) != count or any(source != self.origins[artifact][key] or occurrences != 1
                                                  for key, source, occurrences in provenance):
                    raise RuntimeError(f'Independent provenance oracle failed in {artifact}')
                result[artifact] = {'rows': count, 'full_public_fields_sha256': digest_result.hexdigest()}
        return result

    def check_csv(self, artifact, path):
        expected = dict(self.expected[artifact])
        ids = set()
        with path.open(newline='') as contents:
            for row in csv.DictReader(contents, delimiter=';'):
                if set(row) != set(self.columns[artifact]) | ({'id'} if 'id' in row else set()):
                    raise RuntimeError(f'CSV schema mismatch: {artifact}')
                values = {name: None if row[name] == 'NULL' else row[name] for name in self.columns[artifact]}
                material = [values[name] for name in self.keys[artifact]]
                key = hashlib.sha256(json.dumps(material, separators=(',', ':')).encode()).hexdigest()
                wanted = expected.pop(key, None)
                if wanted is None or values != {name: None if value is None else str(value) for name, value in wanted.items()}:
                    raise RuntimeError(f'Published CSV oracle mismatch: {artifact}')
                if 'id' in row:
                    slot = int(row['id'])
                    if slot <= 0 or slot in ids:
                        raise RuntimeError(f'Invalid public slots: {artifact}')
                    ids.add(slot)
        if expected:
            raise RuntimeError(f'Published CSV missing rows: {artifact}')


class MemorySampler:
    def __init__(self, pid):
        self.pid, self.peak_rss_kib, self.peak_hwm_kib = pid, 0, 0
        self.failure = None
        self.stop = threading.Event()
        self.worker = threading.Thread(target=self.sample, name='capacity-memory-sampler', daemon=False)
        self.worker.start()

    def sample(self):
        try:
            while not self.stop.wait(.05):
                status = Path(f'/proc/{self.pid}/status').read_text()
                self.peak_rss_kib = max(self.peak_rss_kib, int(re.search(r'^VmRSS:\s+(\d+)', status, re.M)[1]))
                self.peak_hwm_kib = max(self.peak_hwm_kib, int(re.search(r'^VmHWM:\s+(\d+)', status, re.M)[1]))
        except Exception as failure:
            self.failure = failure

    def close(self):
        self.stop.set()
        self.worker.join(timeout=5)
        if self.worker.is_alive() or self.failure or self.peak_rss_kib <= 0:
            raise RuntimeError('Capacity memory sampler failed') from self.failure


class Share:
    def __init__(self, endpoint, environment, root):
        self.environment = environment
        self.share = '//' + endpoint['host'] + '/' + endpoint['share']
        descriptor, path = tempfile.mkstemp(prefix='smb-auth-', dir=root)
        self.auth = Path(path)
        with os.fdopen(descriptor, 'w') as contents:
            contents.write('username = ' + environment['SMB_USER'] + '\npassword = ' + environment['SMB_PASSWORD'] + '\n')
            if environment.get('SMB_DOMAIN'):
                contents.write('domain = ' + environment['SMB_DOMAIN'] + '\n')

    def run(self, commands):
        result = subprocess.run(['smbclient', self.share, '-A', str(self.auth), '--client-protection=encrypt',
                                 '-c', commands], capture_output=True, text=True, timeout=35)
        output = result.stdout + result.stderr
        for value in self.environment.values():
            if value:
                output = output.replace(value, '[REDACTED]')
        if result.returncode or 'NT_STATUS_' in output:
            raise RuntimeError('SMB qualification failed: ' + output)

    def close(self):
        self.auth.unlink()


def pin_boot_jar(source, root, identity):
    """Verify boot class/library bytes against the measured frozen runtime."""
    target = root / 'ioc-app.jar'
    shutil.copyfile(source, target)
    with zipfile.ZipFile(target) as archive:
        actual = {}
        for name in archive.namelist():
            if name.endswith('/'):
                continue
            if name.startswith('BOOT-INF/classes/'):
                key = 'app-classes/' + name.removeprefix('BOOT-INF/classes/')
            elif name.startswith('BOOT-INF/lib/'):
                key = 'lib/' + name.removeprefix('BOOT-INF/lib/')
            else:
                continue
            actual[key] = hashlib.sha256(archive.read(name)).hexdigest()
        expected = {key: value for key, value in identity['runtime_files'].items()
                    if key.startswith(('app-classes/', 'lib/'))}
        if actual != expected:
            raise ValueError('Boot jar differs from the frozen production classes/libraries')
        factories = archive.read('META-INF/spring.factories')
    return target, {'sha256': digest(target), 'spring_factories_sha256': hashlib.sha256(factories).hexdigest(),
                    'frozen_class_library_bytes_equal': True}


def stand(args, root, report):
    _, identity = COMPARISON.frozen_runtime(args.runtime.resolve())
    jar, jar_identity = pin_boot_jar(args.jar.resolve(), root, identity)
    config = yaml.safe_load(args.config.read_text())
    endpoint = config['ioc']['sync']['endpoints'][0]['smb']
    if endpoint['username'] != '${SMB_USER}' or endpoint['password'] != '${SMB_PASSWORD}':
        raise ValueError('Use credential placeholders in the stand policy')
    environment = {}
    for line in args.environment.read_text().splitlines():
        if '=' in line and not line.lstrip().startswith('#'):
            key, value = line.split('=', 1)
            if key.strip() in ('SMB_USER', 'SMB_PASSWORD', 'SMB_DOMAIN'):
                environment[key.strip()] = ' '.join(shlex.split(value))
    namespace = '.ioc-capacity-' + uuid.uuid4().hex
    config['server']['port'] = args.port
    config['logging'] = {'level': {'root': 'WARN', 'com.iocextractor': 'WARN',
                                  'com.iocextractor.observability.logging.LoggingPipelineObserver': 'DEBUG'}}
    config['ioc']['sync']['fetch']['sources'][0]['remote-path'] = '/' + namespace + '/send'
    for target in config['ioc']['sync']['publish']['targets']:
        target['remote-path'] = '/' + namespace + '/get'
    for source in config['ioc']['dataframe-import']['sources']:
        if source['transport'] == 'smb':
            source['location'] = namespace + '/import'
    private_config = root / 'application.yml'
    private_config.write_text(yaml.safe_dump(config, sort_keys=False, allow_unicode=True))
    logging_config = root / 'logback-capacity.xml'
    shutil.copyfile(args.runtime.resolve() / 'test-resources/logback-capacity.xml', logging_config)
    report.update(runtime=identity, boot_jar=jar_identity, logging_config_sha256=digest(logging_config),
                  stand_policy_sha256=digest(args.config), isolated_policy_sha256=digest(private_config),
                  policy_changes=['Private state/cwd, loopback port, own SMB directories, logging only'],
                  namespace=namespace, document_sha256=digest(args.document),
                  primary_flags=['-Xms128m', '-Xmx512m'], initial_state='empty private service and canonical databases',
                  cpu_affinity=sorted(os.sched_getaffinity(0))[:2], cgroup={name: Path('/sys/fs/cgroup', name).read_text().strip()
                      for name in ('cpu.max', 'memory.max') if Path('/sys/fs/cgroup', name).exists()})
    oracle = FixtureOracle(config, report['document_sha256'])
    oracle.feed(args.document.read_text())
    report['input_occurrences'] = oracle.observations
    manifest_path = Path(str(args.document) + '.manifest.json')
    if manifest_path.exists():
        manifest = json.loads(manifest_path.read_text())
        if manifest['sha256'] != report['document_sha256'] or manifest['inputRows'] != oracle.observations:
            raise ValueError('Physical input/fixture manifest mismatch')
        report['input_manifest'] = manifest
    process, sampler, share = None, None, None
    namespace_owned = False
    try:
        share = Share(endpoint, environment, root)
        folders = [namespace, namespace + '/send', namespace + '/get', namespace + '/import',
                   namespace + '/import/.ioc-managed-import']
        folders += [namespace + '/import/.ioc-managed-import/' + part
                    for part in ('processing', 'terminal', 'quarantine', 'probe')]
        for folder in folders:
            share.run('mkdir "' + folder + '"')
            namespace_owned = True
        with (root / 'daemon.log').open('w') as log:
            command = ['taskset', '-c', ','.join(map(str, report['cpu_affinity'])), 'java', '-Xms128m', '-Xmx512m',
                       '-Dlogging.config=file:' + str(logging_config),
                       '-Dspring.config.additional-location=file:' + str(private_config), '-jar', str(jar),
                       '--ioc.runtime.mode=daemon']
            process = subprocess.Popen(command, cwd=root,
                env=dict(os.environ, **environment, DEBUG='false', TRACE='false', LOGGING_LEVEL_ROOT='WARN'),
                stdout=log, stderr=subprocess.STDOUT)
            sampler = MemorySampler(process.pid)
            opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
            deadline = time.monotonic() + 90
            while time.monotonic() < deadline:
                if process.poll() is not None:
                    raise RuntimeError('Private capacity daemon exited during startup; see daemon.log')
                try:
                    with opener.open(f'http://127.0.0.1:{args.port}/actuator/health', timeout=1) as response:
                        health = json.load(response)
                        components = health.get('components', {})
                        # CHANGE_NOTIFY is an optional accelerator. Resource exhaustion
                        # degrades its health while polling remains the correctness path.
                        polling_ready = health['status'] == 'DEGRADED' and components \
                            and components.get('smbTransport', {}).get('status') == 'DEGRADED' \
                            and all(value['status'] == 'UP' for name, value in components.items()
                                    if name != 'smbTransport')
                        if health['status'] == 'UP' or polling_ready:
                            report['startup_health'] = {'status': health['status'],
                                'component_statuses': {name: value['status'] for name, value in components.items()},
                                'smb_transport': components.get('smbTransport', {})}
                            break
                except (OSError, ValueError):
                    pass
                time.sleep(.2)
            else:
                raise RuntimeError('Private capacity readiness timeout')
            dataframe = root / 'var/db/ioc-dataframe.db'
            service = root / 'var/db/ioc-service.db'
            report['empty_state'] = snapshot_state(dataframe, service, root / 'empty-state', args.retain_state)
            share.run('put "' + str(args.document.resolve()) + '" "' + namespace + '/send/document.html.part"')
            start = time.monotonic()
            share.run('rename "' + namespace + '/send/document.html.part" "' + namespace + '/send/document.html"')
            completed = wait_publications(share, process, service, dataframe, root, oracle, config, start, args.timeout)
            report['document'] = {'complete_smb_cycle_seconds': time.monotonic() - start, 'publications': completed,
                                  'oracle': oracle.check_database(dataframe)}
            if args.imports:
                report['imports'] = qualify_imports(share, process, root, service, dataframe, oracle, config, namespace, args.timeout)
            report['schemas'] = {name: rows(root / 'var/db' / name, 'PRAGMA user_version')[0]['user_version']
                                 for name in ('ioc-dataframe.db', 'ioc-service.db')}
            events = [json.loads(line) for line in (root / 'daemon.log').read_text().splitlines() if line.startswith('{"@timestamp"')]
            phases = [event for event in events if event.get('event', {}).get('action') == 'stage_complete']
            if len(phases) != 6:
                raise RuntimeError('Missing complete document phase anchors')
            report['document']['pipeline_phases_nanos'] = {event['ioc']['stage']: event['event']['duration'] for event in phases}
            sampler.close()
            report['memory'] = {'sampled_peak_current_rss_kib': sampler.peak_rss_kib,
                                'process_vm_hwm_kib': report_sampler_rss(process.pid),
                                'scope': 'Process RSS including startup, not caller allocation/live heap/cgroup charge; affinity 2 CPUs, no private cgroup quota.'}
            sampler = None
    finally:
        cleanup_errors = []
        if sampler:
            try:
                sampler.close()
            except Exception as failure:
                cleanup_errors.append(str(failure))
        if process and process.poll() is None:
            process.terminate()
            try:
                process.wait(timeout=35)
            except subprocess.TimeoutExpired:
                process.kill(); process.wait(timeout=5)
                cleanup_errors.append('Daemon required forced termination')
        report['workers_terminated'] = process is None or process.poll() is not None
        if report.get('document') and report['workers_terminated']:
            try:
                report['populated_state'] = snapshot_state(root / 'var/db/ioc-dataframe.db',
                    root / 'var/db/ioc-service.db', root / 'populated-state', args.retain_state)
            except Exception as failure:
                cleanup_errors.append('State snapshot failed: ' + str(failure))
        if share:
            try:
                # Only this harness-created namespace; never the operator's send/import/get.
                if namespace_owned:
                    share.run('deltree "' + namespace + '"')
                    report['remote_namespace_removed'] = True
            except Exception as failure:
                cleanup_errors.append(str(failure))
            finally:
                share.close()
        if cleanup_errors:
            report['cleanup_errors'] = cleanup_errors
            raise RuntimeError('Capacity cleanup failed: ' + '; '.join(cleanup_errors))


def report_sampler_rss(pid):
    return int(re.search(r'^VmHWM:\s+(\d+)', Path(f'/proc/{pid}/status').read_text(), re.M)[1])


def wait_publications(share, process, service, dataframe, root, oracle, config, start, timeout):
    profiles = {profile['name']: profile['artifacts'] for profile in config['ioc']['export']['profiles']}
    completed = {}
    while time.monotonic() - start < timeout:
        if process.poll() is not None:
            raise RuntimeError('Private capacity daemon exited during processing')
        if not service.exists() or not dataframe.exists():
            time.sleep(.2); continue
        # A profile can legitimately export between artifact transactions; pin the
        # required revisions only after this document's canonical run is complete.
        if not rows(service, 'SELECT run_id FROM ingest_run WHERE status=?', ('COMPLETED',)):
            time.sleep(.2); continue
        revisions = {row['artifact']: row['revision'] for row in rows(dataframe, 'SELECT * FROM artifact_revision')}
        for profile, artifacts in profiles.items():
            if profile in completed:
                continue
            publications = rows(service, 'SELECT * FROM publish_ledger WHERE profile=? AND status=? ORDER BY created_at DESC', (profile, 'SUCCEEDED'))
            for publication in publications:
                folder = publication['remote_path']
                target = root / 'readback' / profile / publication['slice_name']
                target.mkdir(parents=True, exist_ok=True)
                for name in ('_SUCCESS', 'manifest.json'):
                    share.run('get "' + folder + '/' + name + '" "' + str(target / name) + '"')
                marker = (target / '_SUCCESS').read_text().strip()
                if marker != publication['manifest_sha256'] or digest(target / 'manifest.json') != marker:
                    raise RuntimeError('Published marker/manifest digest mismatch')
                manifest = json.loads((target / 'manifest.json').read_text())
                entries = {entry['artifact']: entry for entry in manifest['artifacts']}
                if set(entries) != set(artifacts):
                    raise RuntimeError('Published artifact membership mismatch')
                if any(entry['coverage']['revision'] != revisions.get(artifact, 0) for artifact, entry in entries.items()):
                    continue
                for artifact, entry in entries.items():
                    if Path(entry['file']).name != entry['file']:
                        raise RuntimeError('Unsafe manifest filename')
                    path = target / entry['file']
                    share.run('get "' + folder + '/' + entry['file'] + '" "' + str(path) + '"')
                    if digest(path) != entry['sha256'] or entry['rows'] != len(oracle.expected[artifact]):
                        raise RuntimeError('Published CSV hash/count mismatch')
                    oracle.check_csv(artifact, path)
                completed[profile] = {'seconds_from_handoff': time.monotonic() - start,
                                      'slice_id': publication['slice_id'], 'status': publication['status'],
                                      'manifest_sha256': marker, 'artifacts': manifest['artifacts']}
                break
        if len(completed) == len(profiles):
            return completed
        time.sleep(.2)
    raise RuntimeError('Full five-artifact publication timeout')


def qualify_imports(share, process, root, service, dataframe, oracle, config, namespace, timeout):
    contracts = config['ioc']['dataframe-import']['contracts']
    values = {
        'masks': {'mask': 'https://Preserved.Example.org:9090/api?q=1', 'url_match': 'u:hEX,dEX', 'source': 'Imported Mask', 'id': '900001'},
        'ip_list': {'ip': '192.0.2.180', 'score': '20', 'source': 'First Import Source', 'description': 'Original', 'id': '900001'},
        'hashes': {'hash_md5': 'a' * 32, 'source': 'Imported Hash', 'id': '900001'},
        'address_blacklist': {'forbidden_url': 'https://Preserved.Example.org:9090/api?q=1'},
        'ioc_aggregate': {'name': 'Original Case', 'url_match': 'https://Preserved.Example.org:9090/api?q=1', 'host_match': 'Preserved.Example.org'}}
    results = []
    start = time.monotonic()
    for contract in contracts:
        artifact = contract['artifacts'][0]['name']
        header = contract['recognition']['required-columns']
        fixture = root / (artifact + '.csv')
        value = values[artifact]
        duplicate = dict(value)
        second = None
        if artifact == 'ip_list':
            duplicate.update(id='900002', source='Ignored Duplicate Source', description='Ignored')
            second = dict(value, id='900003', score='21')
        with fixture.open('w', newline='') as output:
            writer = csv.writer(output, delimiter=';')
            writer.writerow(header)
            writer.writerow([value.get(name, 'NULL') for name in header])
            writer.writerow([duplicate.get(name, 'NULL') for name in header])
            if second:
                writer.writerow([second.get(name, 'NULL') for name in header])
        remote = namespace + '/import/' + fixture.name
        share.run('put "' + str(fixture) + '" "' + remote + '.part"')
        share.run('rename "' + remote + '.part" "' + remote + '"')
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            if process.poll() is not None:
                raise RuntimeError('Private daemon exited during import')
            deliveries = rows(service, 'SELECT * FROM import_delivery WHERE contract_id=? AND state=? ORDER BY sequence_no DESC', (contract['id'], 'TERMINAL'))
            if deliveries:
                delivery = deliveries[0]
                accepted = 2 if second else 1
                if delivery['terminal_outcome'] != 'SUCCEEDED' or delivery['stage_accepted_rows'] != accepted or delivery['stage_rejected_rows'] != 0:
                    raise RuntimeError('AS_IS delivery outcome mismatch: ' + artifact)
                receipt = rows(dataframe, 'SELECT * FROM import_commit WHERE delivery_id=?', (delivery['delivery_id'],))
                if len(receipt) != 1 or receipt[0]['outcome'] != 'COMMITTED' or receipt[0]['accepted_rows'] != accepted:
                    raise RuntimeError('AS_IS canonical receipt mismatch: ' + artifact)
                results.append({'artifact': artifact, 'input_sha256': digest(fixture), 'delivery_id': delivery['delivery_id'],
                                'terminal_outcome': delivery['terminal_outcome'], 'receipt_outcome': receipt[0]['outcome'],
                                'accepted_rows': accepted, 'duplicate_rows': 1})
                oracle.add(artifact, {name: value.get(name) for name in oracle.columns[artifact]},
                           source_key='dataframe-import:trusted-smb')
                if second:
                    oracle.add(artifact, {name: second.get(name) for name in oracle.columns[artifact]},
                               source_key='dataframe-import:trusted-smb')
                    slots = rows(dataframe, "SELECT requested_slot,assigned_slot FROM import_slot_resolution "
                                 "WHERE delivery_id=? ORDER BY source_row_number", (delivery['delivery_id'],))
                    if [(row['requested_slot'], row['assigned_slot']) for row in slots] != [(900001, 900001), (900003, 900003)]:
                        raise RuntimeError('IP duplicate/slot gap contract changed')
                    results[-1]['slot_resolutions'] = slots
                break
            time.sleep(.2)
        else:
            raise RuntimeError('AS_IS import timeout: ' + artifact)
    return {'cases': results, 'complete_batch_smb_seconds': time.monotonic() - start,
            'oracle': oracle.check_database(dataframe),
            'publications': wait_publications(share, process, service, dataframe, root, oracle, config, start, timeout)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--mode', choices=('sql', 'stand', 'phases', 'state'), required=True)
    parser.add_argument('--runtime', type=Path, required=True)
    parser.add_argument('--jar', type=Path, help='Bootable production jar with the frozen class/library bytes; required in stand mode')
    parser.add_argument('--workspace', type=Path, required=True)
    parser.add_argument('--config', type=Path)
    parser.add_argument('--environment', type=Path)
    parser.add_argument('--document', type=Path)
    parser.add_argument('--imports', action='store_true')
    parser.add_argument('--port', type=int, default=18204)
    parser.add_argument('--timeout', type=int, default=180)
    parser.add_argument('--agent', type=Path, help='Common diagnostic agent jar; required for phases')
    parser.add_argument('--rows', type=int, choices=(10000, 100000), default=10000)
    parser.add_argument('--pairs', type=int, default=5)
    parser.add_argument('--source-state', type=Path, help='Completed private stand workspace; required for state mode')
    parser.add_argument('--database', type=Path, help='Optional private public-path SQLite state for read-only SQL plan checks')
    parser.add_argument('--retain-state', action='store_true',
                        help='Keep generated databases/CSVs; stand snapshots are compressed. Default removes state after checks, including failures.')
    args = parser.parse_args()
    root = args.workspace.resolve()
    if not root.is_relative_to(REPO / '.dev') or root.exists() and any(root.iterdir()):
        parser.error('Use a new empty repo-local .dev workspace')
    if args.mode == 'stand' and any(value is None for value in (args.jar, args.config, args.environment, args.document)):
        parser.error('Stand mode needs --jar, --config, --environment and --document')
    if args.mode == 'phases' and (args.agent is None or args.pairs < 1):
        parser.error('Phases mode needs --agent and positive --pairs')
    if args.mode == 'state' and (args.source_state is None or not args.source_state.resolve().is_relative_to(REPO / '.dev')):
        parser.error('State mode needs a private repo-local --source-state')
    if args.database and (args.mode != 'sql' or not args.database.is_file()
                         or not args.database.resolve().is_relative_to(REPO / '.dev')):
        parser.error('--database requires SQL mode and an existing private .dev state')
    if args.mode in ('stand', 'phases') and shutil.disk_usage(REPO / '.dev').free < 1024 ** 3:
        parser.error('Less than 1 GiB free for capacity state; no JVM started')
    root.mkdir(parents=True, exist_ok=True)
    COMPARISON.COMPARISON.mark_owned_workspace(root)
    report = {'mode': args.mode, 'status': 'RUNNING', 'driver_sha256': digest(__file__)}
    try:
        if args.mode == 'sql':
            sql_screen(args, root, report)
        elif args.mode == 'phases':
            phases(args, root, report)
        elif args.mode == 'state':
            source = args.source_state.resolve()
            evidence = json.loads((source / 'report.json').read_text())
            if evidence['status'] != 'PASS' or not evidence['workers_terminated']:
                raise ValueError('Only a completed successful private stand state can be frozen')
            identity = COMPARISON.frozen_runtime(args.runtime.resolve())[1]
            if identity != evidence['runtime']:
                raise ValueError('State/runtime identity mismatch')
            report.update(runtime=identity, source_report_sha256=digest(source / 'report.json'),
                          document_sha256=evidence['document_sha256'], input_occurrences=evidence['input_occurrences'],
                          stand_policy_sha256=evidence['stand_policy_sha256'],
                          state=snapshot_state(source / 'var/db/ioc-dataframe.db',
                              source / 'var/db/ioc-service.db', root / 'state', retain=True))
        else:
            stand(args, root, report)
        report['status'] = 'PASS'
    except Exception as failure:
        report.update(status='FAIL', failure=str(failure))
        raise
    finally:
        if args.mode == 'stand' and report.get('workers_terminated', True):
            report['retention'] = COMPARISON.COMPARISON.discard_generated_state(
                root, args.retain_state, stand=True)
        write_report(root, 'report.json', report)
    print('PASS: capacity ' + args.mode + '; ' + str(root / 'report.json'))


if __name__ == '__main__':
    main()
