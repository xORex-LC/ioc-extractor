#!/usr/bin/env python3
"""CAP-6 private, resource-limited service qualification; failures retain facts, never databases."""
import argparse
from contextlib import closing
import csv
from datetime import datetime
import hashlib
from html import escape
import importlib.util
import json
import os
from pathlib import Path
import re
import shutil
import sqlite3
import statistics
import subprocess
import sys
import tempfile
import threading
import time
import urllib.request
import uuid
import zipfile

import yaml

sys.dont_write_bytecode = True
REPO = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location('capacity_base', Path(__file__).with_name('data-processing-capacity.py'))
BASE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(BASE)
INPUT_SPEC = importlib.util.spec_from_file_location('capacity_inputs', Path(__file__).with_name('service-capacity-inputs.py'))
INPUTS = importlib.util.module_from_spec(INPUT_SPEC)
INPUT_SPEC.loader.exec_module(INPUTS)
FLAGS = ['-Xms128m', '-Xmx512m']


class WorkloadFailure(RuntimeError):
    """A fatal workload result, distinct from a collector or external provisioning failure."""


def jdk_tool(name):
    binary = shutil.which(name)
    if binary:
        return [binary]
    # This host has the complete JDK modules but omits diagnostic binary launchers.
    modules = {'jstat': 'jdk.jcmd/sun.tools.jstat.Jstat', 'jcmd': 'jdk.jcmd/sun.tools.jcmd.JCmd'}
    return [shutil.which('java'), '-m', modules[name]]


def command(arguments, timeout=40):
    result = subprocess.run(arguments, capture_output=True, text=True, timeout=timeout, check=False)
    if result.returncode:
        # No environment values/credentials are passed as command arguments.
        raise RuntimeError(f'{arguments[0]} failed: {result.stderr[-2000:]}')
    return result.stdout.strip()


def save(path, value):
    staging = path.with_suffix(path.suffix + '.part')
    staging.write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n')
    staging.replace(path)


def normalize(values):
    return {key: None if value is None or value == 'NULL' else str(value) for key, value in values.items()}


class DiskOracle(BASE.FixtureOracle):
    """Independent fixture semantics with disk-backed expected rows and provenance, bounded Python memory."""
    def __init__(self, config, path):
        super().__init__(config)
        self.db = sqlite3.connect(path)
        self.db.execute('PRAGMA cache_size=-2048')
        self.db.execute('CREATE TABLE expected(artifact TEXT,key TEXT,fields TEXT,PRIMARY KEY(artifact,key)) WITHOUT ROWID')
        self.db.execute('CREATE TABLE origins(artifact TEXT,key TEXT,source TEXT,PRIMARY KEY(artifact,key,source)) WITHOUT ROWID')
        self.db.execute('CREATE TABLE seen(key TEXT PRIMARY KEY,slot INTEGER UNIQUE) WITHOUT ROWID')
        self.source_key = None
        self.headers = {item['name']: [column['name'] for column in item['columns']]
                        for item in config['ioc']['sink']['artifacts']}

    def add(self, artifact, supplied, last=False, source_key=None):
        values = normalize({name: supplied.get(name) for name in self.columns[artifact]})
        key = self.key(artifact, values)
        clause = 'DO UPDATE SET fields=excluded.fields' if last else 'DO NOTHING'
        self.db.execute('INSERT INTO expected VALUES(?,?,?) ON CONFLICT(artifact,key) ' + clause,
                        (artifact, key, json.dumps(values, sort_keys=True, ensure_ascii=False)))
        self.db.execute('INSERT OR IGNORE INTO origins VALUES(?,?,?)',
                        (artifact, key, source_key or self.source_key))

    def key(self, artifact, values):
        return hashlib.sha256(json.dumps([values[name] for name in self.keys[artifact]],
                                          separators=(',', ':')).encode()).hexdigest()

    def counts(self):
        self.db.commit()
        return {artifact: self.db.execute('SELECT count(*) FROM expected WHERE artifact=?', (artifact,)).fetchone()[0]
                for artifact in BASE.ARTIFACTS}

    def wanted(self, artifact, key, values):
        row = self.db.execute('SELECT fields FROM expected WHERE artifact=? AND key=?', (artifact, key)).fetchone()
        if row is None or json.loads(row[0]) != values:
            raise RuntimeError(f'Independent fields/key oracle failed: {artifact} {key}')

    def check_database(self, database):
        result = {}
        with closing(sqlite3.connect(f'file:{database}?mode=ro', uri=True)) as actual:
            actual.execute('BEGIN')
            for artifact, expected_count in self.counts().items():
                names = self.columns[artifact]
                checksum, count = hashlib.sha256(), 0
                for row in actual.execute(f'SELECT row_key,{",".join(names)},id,_lifecycle_id,_valid_until_epoch_ms FROM {artifact} ORDER BY row_key'):
                    key, *fields = row
                    values = normalize(dict(zip(names, fields[:len(names)])))
                    self.wanted(artifact, key, values)
                    if any(value <= 0 for value in fields[-3:]):
                        raise RuntimeError(f'Invalid canonical identity/lifecycle: {artifact}')
                    checksum.update(json.dumps([key, values], sort_keys=True, ensure_ascii=False).encode() + b'\n')
                    count += 1
                if count != expected_count:
                    raise RuntimeError(f'Missing canonical rows: {artifact}: {count}/{expected_count}')
                origins_count = 0
                for key, source, occurrences in actual.execute(f'SELECT c.row_key,s.source_key,s.occurrences FROM {artifact} c JOIN {artifact}_sources s ON s.row_id=c.id'):
                    if occurrences != 1 or self.db.execute('SELECT 1 FROM origins WHERE artifact=? AND key=? AND source=?',
                                                          (artifact, key, source)).fetchone() is None:
                        raise RuntimeError(f'Independent provenance oracle failed: {artifact}')
                    origins_count += 1
                wanted_origins = self.db.execute('SELECT count(*) FROM origins WHERE artifact=?', (artifact,)).fetchone()[0]
                if origins_count != wanted_origins:
                    raise RuntimeError(f'Missing provenance: {artifact}')
                result[artifact] = {'rows': count, 'origins': origins_count, 'public_fields_sha256': checksum.hexdigest()}
        return result

    def check_csv(self, artifact, path, database, profile):
        self.db.execute('DELETE FROM seen')
        count = 0
        with path.open(newline='') as contents, closing(sqlite3.connect(f'file:{database}?mode=ro', uri=True)) as canonical:
            reader = csv.DictReader(contents, delimiter=';')
            names = self.columns[artifact]
            has_id = 'id' in self.headers[artifact]
            if reader.fieldnames != self.headers[artifact]:
                raise RuntimeError(f'CSV schema mismatch: {artifact}')
            for row in reader:
                values = normalize({name: row[name] for name in names})
                key = self.key(artifact, values)
                self.wanted(artifact, key, values)
                slot = int(row['id']) if has_id else None
                if has_id:
                    assignment = canonical.execute(f'SELECT id FROM {artifact} WHERE row_key=?', (key,)).fetchone() if profile is None else canonical.execute(f'SELECT a.slot FROM export_slot_assignment a JOIN {artifact} c '
                        'ON c._lifecycle_id=a.lifecycle_id WHERE a.profile=? AND a.artifact=? AND c.row_key=?',
                        (profile, artifact, key)).fetchone()
                    if slot <= 0 or assignment != (slot,):
                        raise RuntimeError(f'Public slot/registry mismatch: {artifact}')
                try:
                    self.db.execute('INSERT INTO seen VALUES(?,?)', (key, slot))
                except sqlite3.IntegrityError as failure:
                    raise RuntimeError(f'Duplicate public row/slot: {artifact}') from failure
                count += 1
            if count != self.counts()[artifact]:
                raise RuntimeError(f'CSV missing rows: {artifact}')
        self.db.execute('DELETE FROM seen')
        self.db.commit()

    def close(self):
        self.db.close()


def fixture(path, count, shape='mostly-unique', seed=43):
    """Generate HTML or an actual DOCX package incrementally from the same six-type paragraph corpus."""
    def paragraphs():
        for number in range(count):
            if number % 250 == 0:
                yield 'h2', f'БИБ-{number // 250 + 1:04d}'
            ordinal = number % 60 if shape == 'collapse' else number
            if shape == 'mostly-unique' and number % 10 == 9:
                ordinal -= 6
            index, kind = ordinal // 6 + 1, ordinal % 6
            host = f'ioc-{index}-s{seed:x}.example.test'
            ip = 10 * 2**24 + (seed * 100000 + index) % (2**24 - 1)
            values = ['.'.join(str((ip >> shift) & 255) for shift in (24, 16, 8, 0)), host,
                      f'https://{host}/path/{index}?sample={index}',
                      hashlib.md5(f'{seed}:{index}:md5'.encode()).hexdigest().upper(),
                      hashlib.sha1(f'{seed}:{index}:sha1'.encode()).hexdigest().upper(),
                      hashlib.sha256(f'{seed}:{index}:sha256'.encode()).hexdigest().upper()]
            value = values[kind]
            if number % 5 == 0 and kind < 3:
                value = value.replace('https://', 'hxxps[:]//').replace('.', '[.]')
            yield 'p', f'sample-{number + 1} :: {value}'
    if path.suffix == '.html':
        with path.open('w') as output:
            output.write('<!doctype html><html><head><meta charset="utf-8"></head><body>\n')
            for tag, text in paragraphs():
                output.write(f'<{tag}>{escape(text)}</{tag}>\n')
            output.write('</body></html>\n')
    elif path.suffix == '.docx':
        with zipfile.ZipFile(path, 'w', compression=zipfile.ZIP_DEFLATED) as package:
            package.writestr('[Content_Types].xml', '<?xml version="1.0"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/></Types>')
            package.writestr('_rels/.rels', '<?xml version="1.0"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>')
            with package.open('word/document.xml', 'w') as output:
                output.write(b'<?xml version="1.0" encoding="UTF-8"?><w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>')
                for _, text in paragraphs():
                    output.write(f'<w:p><w:r><w:t>{escape(text)}</w:t></w:r></w:p>'.encode())
                output.write(b'<w:sectPr/></w:body></w:document>')
    else:
        raise ValueError('Fixture requires .html or .docx')
    return {'rows': count, 'sections': (count + 249) // 250, 'shape': shape, 'seed': seed,
            'format': path.suffix[1:], 'bytes': path.stat().st_size, 'sha256': BASE.digest(path)}


def feed_fixture(oracle, path):
    oracle.source_key = BASE.digest(path)
    oracle.source = None
    if path.suffix == '.html':
        with path.open() as source:
            while chunk := source.read(65536):
                oracle.feed(chunk)
    else:
        import xml.etree.ElementTree as ET
        with zipfile.ZipFile(path) as package, package.open('word/document.xml') as source:
            parents = []
            for event, element in ET.iterparse(source, events=('start', 'end')):
                if event == 'start':
                    parents.append(element)
                    continue
                if element.tag.endswith('}p'):
                    value = ''.join(element.itertext())
                    tag = 'h2' if value.startswith('БИБ-') else 'p'
                    oracle.feed(f'<{tag}>{escape(value)}</{tag}>')
                    parents[-2].remove(element)
                    element.clear()
                parents.pop()
    oracle.db.commit()


def counters(text):
    return {key: int(value) for key, value in (line.split() for line in text.splitlines())}


def pressure(text):
    return {line.split()[0]: int(re.search(r'total=(\d+)', line)[1]) for line in text.splitlines()}


def difference(before, after):
    if set(before) != set(after) or any(after[key] < before[key] for key in before):
        raise RuntimeError('Missing or regressed resource counters')
    return {key: after[key] - before[key] for key in before}


def jvm_sample(pid):
    """JDK 21 HotSpot counters; reject schema drift rather than silently losing columns."""
    lines = command(jdk_tool('jstat') + ['-gc', str(pid)], timeout=5).splitlines()
    if len(lines) != 2 or len(lines[0].split()) != len(lines[1].split()):
        raise RuntimeError('Invalid jstat counter snapshot')
    values = dict(zip(lines[0].split(), lines[1].split()))
    required = ('S0U', 'S1U', 'EU', 'OU', 'S0C', 'S1C', 'EC', 'OC', 'MU', 'MC', 'YGC', 'FGC', 'GCT')
    if any(name not in values for name in required):
        raise RuntimeError('Missing JDK 21 jstat counters')
    values = {name: float(values[name]) for name in required}
    return {'heap_used_bytes': int(sum(values[name] for name in ('S0U', 'S1U', 'EU', 'OU')) * 1024),
            'heap_committed_bytes': int(sum(values[name] for name in ('S0C', 'S1C', 'EC', 'OC')) * 1024),
            # CCS is part of metaspace; do not double count it.
            'metaspace_used_bytes': int(values['MU'] * 1024), 'metaspace_committed_bytes': int(values['MC'] * 1024),
            'gc': {name: values[name] for name in ('YGC', 'FGC', 'GCT')}, 'sample_monotonic': time.monotonic()}


class ResourceSampler:
    """Fail-closed sampler of one JVM and its effective cgroup; bounded retained aggregate plus JSONL."""
    def __init__(self, pid, cgroup, root):
        self.pid, self.cgroup, self.root = pid, cgroup, root
        self.stop = threading.Event()
        self.failure, self.latest = None, None
        self.lock = threading.Lock()
        self.peak = {}
        self.samples = 0
        self.jvm = None
        self.first = self.read()
        self.latest = self.first
        self.worker = threading.Thread(target=self.run, name='service-capacity-resources', daemon=False)
        self.worker.start()

    def read(self):
        if self.jvm is None or time.monotonic() - self.jvm['sample_monotonic'] >= 1:
            self.jvm = jvm_sample(self.pid)
        status = Path(f'/proc/{self.pid}/status').read_text()
        rss = int(re.search(r'^VmRSS:\s+(\d+)', status, re.M)[1]) * 1024
        hwm = int(re.search(r'^VmHWM:\s+(\d+)', status, re.M)[1]) * 1024
        memory = counters((self.cgroup / 'memory.stat').read_text())
        stat = Path(f'/proc/{self.pid}/stat').read_text().rsplit(')', 1)[1].split()
        paths = [self.root / 'var', self.root / 'dataframe']
        sizes = {'wal_bytes': 0, 'workspace_bytes': 0, 'output_bytes': 0}
        for directory in paths:
            for path in directory.rglob('*'):
                if path.is_file() and not path.is_symlink():
                    try:
                        size = path.stat().st_size
                    except FileNotFoundError:
                        # Owned spool files may be removed between directory enumeration and stat.
                        continue
                    if path.name.endswith('-wal'):
                        sizes['wal_bytes'] += size
                    if any(name in path.parts for name in ('document-preparation', 'workspaces', 'staging')):
                        sizes['workspace_bytes'] += size
                    if path.suffix == '.csv':
                        sizes['output_bytes'] += size
        return dict(monotonic=time.monotonic(), rss_bytes=rss, hwm_bytes=hwm,
            process_cpu_seconds=(int(stat[11]) + int(stat[12])) / os.sysconf('SC_CLK_TCK'),
            anon_bytes=memory['anon'], file_bytes=memory['file'], kernel_bytes=memory['kernel'],
            non_file_bytes=memory['anon'] + memory['kernel'],
            cgroup_current_bytes=int((self.cgroup / 'memory.current').read_text()),
            swap_bytes=int((self.cgroup / 'memory.swap.current').read_text()),
            psi=pressure((self.cgroup / 'memory.pressure').read_text()),
            events=counters((self.cgroup / 'memory.events').read_text()),
            cpu=counters((self.cgroup / 'cpu.stat').read_text()), **sizes, **self.jvm)

    def run(self):
        try:
            with (self.root / 'resources.jsonl').open('w') as output:
                while not self.stop.wait(.1):
                    sample = self.read()
                    with self.lock:
                        self.latest = sample
                        self.samples += 1
                        for name, value in sample.items():
                            if name.endswith('_bytes'):
                                self.peak[name] = max(self.peak.get(name, 0), value)
                    output.write(json.dumps(sample) + '\n')
        except Exception as failure:
            self.failure = failure

    def begin(self):
        with self.lock:
            self.peak.clear()
            self.first = self.latest

    def summary(self):
        if self.failure:
            raise RuntimeError('Resource sampler failed') from self.failure
        with self.lock:
            last = dict(self.latest)
            wall = last['monotonic'] - self.first['monotonic']
            psi = difference(self.first['psi'], last['psi'])
            return {'samples': self.samples, 'sample_interval_seconds': .1, 'wall_seconds': wall,
                    'process_cpu_seconds': last['process_cpu_seconds'] - self.first['process_cpu_seconds'],
                    'peaks': dict(self.peak), 'process_hwm_bytes': last['hwm_bytes'],
                    'cpu_delta': difference(self.first['cpu'], last['cpu']),
                    'gc_delta': difference(self.first['gc'], last['gc']),
                    'jvm_sampling': 'jstat -gc; at most one snapshot/s; metaspace excludes code cache/native allocations',
                    'memory_events_delta': difference(self.first['events'], last['events']),
                    'psi_microseconds_delta': psi, 'psi_full_fraction': psi['full'] / (wall * 1e6) if wall > 0 else None}

    def close(self):
        self.stop.set()
        self.worker.join(5)
        if self.worker.is_alive() or self.failure or self.samples == 0:
            raise RuntimeError('Resource sampler failed or did not terminate') from self.failure


def writer_window(before, after):
    """Bound window maxima without attributing startup/population maxima to new work."""
    result = {}
    for name, current in after.items():
        previous = before.get(name, {key: 0 for key in current})
        delta = difference(previous, current)
        value = {'completed': delta['completed']}
        for kind in ('Hold', 'Wait'):
            maximum, total = 'maximum' + kind + 'Nanos', 'total' + kind + 'Nanos'
            exact = current[maximum] > previous[maximum] or delta['completed'] == 0
            value[total] = delta[total]
            value[maximum + 'UpperBound'] = (current[maximum] if exact else min(current[maximum], delta[total])) if delta['completed'] else 0
            value[maximum + 'IsExact'] = exact
        result[name] = value
    return result


def gate_sample(sample, size):
    limits = {'local_max_seconds': 270 if size >= 1000000 else 45,
              'rss_bytes': 512 * 1024**2, 'non_file_bytes': 576 * 1024**2, 'writer_hold_nanos': 5 * 10**9}
    resource = sample['resources']
    violations = []
    # The window is conservative (cadence remains included), never a fabricated exact Tlocal.
    if sample['local_window_upper_seconds'] > limits['local_max_seconds']:
        violations.append('conservative_local_window')
    for name in ('rss_bytes', 'non_file_bytes'):
        if resource['peaks'][name] > limits[name]:
            violations.append(name)
    if resource['psi_full_fraction'] is None or resource['psi_full_fraction'] > .01:
        violations.append('memory_psi_full')
    if resource['memory_events_delta']['oom'] or resource['memory_events_delta']['oom_kill']:
        violations.append('oom')
    operations = writer_window(sample['health_before']['writerOperations'], sample['health']['writerOperations'])
    for name, value in operations.items():
        if value['maximumHoldNanosUpperBound'] > limits['writer_hold_nanos']:
            violations.append('writer_hold' if value['maximumHoldNanosIsExact'] else 'writer_hold_unresolved')
            break
    control = operations.get('CONTROL', {})
    if control.get('maximumWaitNanosUpperBound', 0) > 10 * 10**9:
        violations.append('control_wait' if control['maximumWaitNanosIsExact'] else 'control_wait_unresolved')
    return {'limits': limits, 'violations': violations, 'status': 'FAIL' if violations else 'SCREEN_PASS',
            'writer_window': operations,
            'scope': 'Conservative complete-window and resource screens; exact Tlocal/remaining G6 criteria remain separate'}


class PrivateUnit:
    """Own exactly one user-systemd JVM; verify applied cgroup ceilings before intake."""
    def __init__(self, root):
        self.root, self.name = root, 'ioc-cap6-' + uuid.uuid4().hex
        self.pid, self.cgroup = None, None
        self.log_offset, self.log_tail = 0, ''

    def start(self, jar, config, environment=None, diagnostic=False):
        arguments = ['systemd-run', '--user', '--quiet', '--unit=' + self.name,
                     '-p', 'WorkingDirectory=' + str(self.root), '-p', 'CPUQuota=200%',
                     '-p', 'MemoryHigh=768M', '-p', 'MemoryMax=1G', '-p', 'TimeoutStopSec=40s',
                     '-p', 'StandardOutput=append:' + str(self.root / 'daemon.log'),
                     '-p', 'StandardError=append:' + str(self.root / 'daemon.log')]
        if environment:
            arguments += ['-p', 'EnvironmentFile=' + str(environment)]
        flags = FLAGS + (['-XX:NativeMemoryTracking=summary',
                         '-XX:StartFlightRecording=filename=' + str(self.root / 'diagnostic.jfr') + ',settings=profile,dumponexit=true'] if diagnostic else [])
        command(arguments + [shutil.which('java'), *flags,
                '-Dlogging.config=file:' + str(self.root / 'logback.xml'),
                '-Dspring.config.additional-location=file:' + str(config), '-jar', str(jar),
                '--ioc.runtime.mode=daemon'])
        for _ in range(100):
            properties = self.properties()
            if int(properties['MainPID']):
                self.pid = int(properties['MainPID'])
                self.cgroup = Path('/sys/fs/cgroup') / properties['ControlGroup'].lstrip('/')
                limits = {name: (self.cgroup / name).read_text().strip()
                          for name in ('cpu.max', 'memory.high', 'memory.max', 'memory.swap.max')}
                quota, period = limits['cpu.max'].split()
                if quota == 'max' or int(quota) != 2 * int(period) or limits['memory.high'] != str(768 * 1024**2) or limits['memory.max'] != str(1024**3):
                    raise RuntimeError('Effective unit limits differ from frozen envelope')
                return {'unit': self.name, 'pid': self.pid, 'cgroup': str(self.cgroup), 'effective_limits': limits,
                        'flags': flags, 'environment_owner': 'systemd EnvironmentFile; never sourced in a shell'}
            self.assert_running()
            time.sleep(.05)
        raise RuntimeError('Private unit did not start a JVM')

    def properties(self):
        output = command(['systemctl', '--user', 'show', self.name,
                          '-p', 'MainPID', '-p', 'ControlGroup', '-p', 'ActiveState', '-p', 'Result', '-p', 'ExecMainStatus'])
        return dict(line.split('=', 1) for line in output.splitlines())

    def assert_running(self):
        # A Java worker can die from OOM while systemd still reports an active JVM.
        # Read incrementally so a failed reference does not wait for its publication timeout.
        path = self.root / 'daemon.log'
        if path.is_file():
            with path.open() as log:
                log.seek(self.log_offset)
                while chunk := log.read(65536):
                    text = self.log_tail + chunk
                    if 'java.lang.OutOfMemoryError' in text:
                        raise WorkloadFailure('Java OutOfMemoryError; measured worker failed (see daemon.log)')
                    if '[SQLITE_FULL]' in text:
                        raise WorkloadFailure('SQLite workspace capacity exhausted (see daemon.log); no failed-screen retries')
                    self.log_tail = text[-128:]
                self.log_offset = log.tell()
        properties = self.properties()
        if properties['ActiveState'] in ('failed', 'inactive'):
            raise RuntimeError('Private unit exited: ' + json.dumps(properties))

    def close(self):
        before = self.properties()
        command(['systemctl', '--user', 'stop', self.name], timeout=55)
        after = self.properties()
        if int(after['MainPID']) != 0:
            raise RuntimeError('Private JVM did not terminate')
        subprocess.run(['systemctl', '--user', 'reset-failed', self.name], capture_output=True, timeout=5, check=False)
        return {'before_stop': before, 'after_stop': after, 'process_terminated': True}


def health(port, component=None):
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    with opener.open(f'http://127.0.0.1:{port}/actuator/health' + ('/' + component if component else ''), timeout=3) as response:
        return json.load(response)


def ready(unit, port):
    started, deadline = time.monotonic(), time.monotonic() + 90
    while time.monotonic() < deadline:
        unit.assert_running()
        try:
            value = health(port)
            if value['status'] == 'UP':
                return {'seconds': time.monotonic() - started, 'health': value}
        except (OSError, ValueError):
            pass
        time.sleep(.2)
    raise RuntimeError('Private service readiness timeout')


def quiet_seconds(config):
    duration = config['ioc']['ingestion']['stability']['quiet-period']
    match = re.fullmatch(r'(\d+(?:\.\d+)?)(ms|s)', duration)
    if not match:
        raise ValueError('Harness requires an explicit seconds/milliseconds quiet-period')
    return float(match[1]) / (1000 if match[2] == 'ms' else 1)


def local_handoff(path, root):
    target = root / 'var/inbox' / path.name
    staging = target.with_suffix(target.suffix + '.part')
    shutil.copyfile(path, staging)
    os.utime(staging, None)
    staging.replace(target)
    return {'monotonic': time.monotonic(), 'epoch_seconds': time.time(), 'kind': 'producer atomic local rename completed'}


def admission_rows(root, config, source_key):
    ledger = config['ioc']['ingestion']['ledger']
    if ledger['type'] == 'jdbc':
        return BASE.rows(root / 'var/db/ioc-service.db',
            'SELECT occurrence_id,phase,created_at_ms,updated_at_ms,terminal_outcome,registration_finalized '
            'FROM document_admission WHERE source_key=?', (source_key,))
    if ledger['type'] != 'file':
        raise ValueError('Capacity requires a durable file or JDBC admission journal')
    result = []
    for path in (root / ledger['path'] / 'document-admission').glob('*.properties'):
        # Selected journal fields are ASCII; timestamps have Properties.store escaped colons.
        fields = dict(line.split('=', 1) for line in path.read_text().splitlines()
                      if line and not line.startswith('#') and '=' in line)
        if fields.get('sourceKey') == source_key:
            result.append({'occurrence_id': fields['observationId'], 'phase': fields['phase'],
                'created_at_ms': datetime.fromisoformat(fields['createdAt'].replace('\\:', ':')).timestamp() * 1000,
                'updated_at_ms': datetime.fromisoformat(fields['updatedAt'].replace('\\:', ':')).timestamp() * 1000,
                'terminal_outcome': fields.get('terminalOutcome'),
                'registration_finalized': int(fields['registrationFinalized'] == 'true')})
    return result


def import_completion(root, manifest):
    deliveries = BASE.rows(root / 'var/db/ioc-service.db',
        'SELECT * FROM import_delivery WHERE snapshot_sha256=? AND state=?', (manifest['sha256'], 'TERMINAL'))
    if not deliveries:
        return None
    if len(deliveries) != 1:
        raise RuntimeError('Unexpected duplicate import delivery')
    delivery = deliveries[0]
    if delivery['terminal_outcome'] != 'SUCCEEDED' or delivery['stage_accepted_rows'] != manifest['accepted_rows'] or delivery['stage_rejected_rows'] != 0:
        raise RuntimeError('AS_IS import outcome/count mismatch')
    receipts = BASE.rows(root / 'var/db/ioc-dataframe.db',
                        'SELECT * FROM import_commit WHERE delivery_id=?', (delivery['delivery_id'],))
    if len(receipts) != 1 or receipts[0]['outcome'] != 'COMMITTED' or receipts[0]['accepted_rows'] != manifest['accepted_rows']:
        raise RuntimeError('AS_IS import commit receipt mismatch')
    if manifest['has_slots']:
        with closing(sqlite3.connect(f'file:{root / "var/db/ioc-dataframe.db"}?mode=ro', uri=True)) as db:
            count = 0
            for row in db.execute('SELECT source_row_number,requested_slot,assigned_slot,outcome FROM import_slot_resolution WHERE delivery_id=? ORDER BY source_row_number', (delivery['delivery_id'],)):
                # The duplicate is last and therefore cannot reclaim an earlier requested slot.
                count += 1
                if row != (count + 1, 900000 + count, 900000 + count, 'EXACT'):
                    raise RuntimeError('Imported sparse slot request/resolution mismatch')
            if count != manifest['accepted_rows']:
                raise RuntimeError('Missing imported slot resolutions')
    return {'delivery': delivery, 'receipt': receipts[0]}


def local_slices(unit, root, oracle, config, source_key, timeout, import_manifest=None):
    service, dataframe = root / 'var/db/ioc-service.db', root / 'var/db/ioc-dataframe.db'
    profiles = {profile['name']: set(profile['artifacts']) for profile in config['ioc']['export']['profiles']}
    deadline, found, transitions = time.monotonic() + timeout, {}, []
    previous = None
    while time.monotonic() < deadline:
        unit.assert_running()
        admissions = [] if import_manifest else admission_rows(root, config, source_key)
        if admissions and admissions[0] != previous:
            previous = admissions[0]
            transitions.append(dict(observed_monotonic=time.monotonic(), **previous))
        if admissions and admissions[0]['phase'] == 'TERMINAL' and admissions[0]['terminal_outcome'] != 'SUCCEEDED':
            raise WorkloadFailure('Document admission reached a failed terminal outcome')
        imported = import_completion(root, import_manifest) if import_manifest else None
        runs = [imported] if imported else ([] if import_manifest else BASE.rows(service, 'SELECT * FROM ingest_run WHERE source_key=? AND status=?', (source_key, 'COMPLETED')))
        if runs:
            projections = BASE.rows(dataframe, 'SELECT * FROM artifact_projection_state')
            if any(row['required_generation'] != row['projected_generation'] for row in projections):
                time.sleep(.1)
                continue
            revisions = {row['artifact']: row['revision'] for row in BASE.rows(dataframe, 'SELECT * FROM artifact_revision')}
            for run in BASE.rows(service, 'SELECT * FROM export_run WHERE status=?', ('COMPLETED',)):
                if run['profile'] not in profiles:
                    continue
                folder = root / 'var/export' / run['profile'] / run['slice_name']
                manifest_path = folder / 'manifest.json'
                if not manifest_path.is_file():
                    continue
                manifest = json.loads(manifest_path.read_text())
                entries = {entry['artifact']: entry for entry in manifest['artifacts']}
                if set(entries) != profiles[run['profile']] or any(entry['coverage']['revision'] != revisions.get(name, 0) for name, entry in entries.items()):
                    continue
                if BASE.digest(manifest_path) != run['manifest_sha256'] or (folder / '_SUCCESS').read_text().strip() != run['manifest_sha256']:
                    raise RuntimeError('Local slice marker/manifest mismatch')
                found[run['profile']] = dict(run=run, manifest=manifest, path=str(folder))
            terminal = imported or admissions and admissions[0]['phase'] == 'TERMINAL' and admissions[0]['registration_finalized'] == 1
            if set(found) == set(profiles) and terminal:
                if admissions and admissions[0]['terminal_outcome'] != 'SUCCEEDED':
                    raise RuntimeError('Document terminal outcome failed')
                # Stop timing before independent output readback/verification.
                return {'complete_monotonic': time.monotonic(), 'profiles': found,
                        'admission_transitions': transitions, 'run': runs[0], 'revisions': revisions, 'projections': projections}
        time.sleep(.1)
    raise RuntimeError('Complete local slices timeout')


def verify_slices(local, oracle, dataframe):
    evidence = {}
    for profile, value in local['profiles'].items():
        folder, manifest = Path(value['path']), value['manifest']
        for entry in manifest['artifacts']:
            if Path(entry['file']).name != entry['file']:
                raise RuntimeError('Unsafe manifest filename')
            path = folder / entry['file']
            if BASE.digest(path) != entry['sha256'] or entry['rows'] != oracle.counts()[entry['artifact']]:
                raise RuntimeError('Local CSV manifest hash/count mismatch')
            oracle.check_csv(entry['artifact'], path, dataframe, profile)
        evidence[profile] = {'manifest_sha256': value['run']['manifest_sha256'], 'artifacts': manifest['artifacts'],
                             'slice_id': value['run']['run_id']}
    return evidence


def private_config(source, root, port):
    config = yaml.safe_load(source.read_text())
    config.setdefault('server', {})['port'] = port
    # All storage and workload paths must remain in this unit's private cwd.
    for store in ('service', 'dataframe'):
        config['ioc']['storage'][store]['url'] = f'jdbc:sqlite:./var/db/ioc-{store}.db'
    config['ioc']['export']['root'] = './var/export'
    config['ioc']['ingestion']['dirs'] = {name: './var/' + name for name in ('inbox', 'processing', 'done', 'failed')}
    config['ioc']['ingestion']['ledger']['path'] = './var/ledger'
    config['ioc'].setdefault('processing', {}).setdefault('workspace', {})['directory'] = './var/document-preparation'
    config['ioc']['dataframe-import'].setdefault('runtime', {})['dirs'] = {
        name: './var/import/' + name for name in ('processing', 'snapshots', 'staging', 'terminal', 'quarantine')}
    filenames = set()
    for artifact in config['ioc']['sink']['artifacts']:
        filename = Path(artifact['path']).name
        if not filename or filename in filenames:
            raise ValueError('Distinct private artifact filenames required')
        filenames.add(filename)
        artifact['path'] = './dataframe/' + filename
    # Retention paths can otherwise escape the private cwd even with intake disabled.
    for index, target in enumerate(config['ioc'].get('maintenance', {}).get('retention', {}).get('targets', [])):
        target['dir'] = './var/retention/' + str(index)
    config['ioc']['sync']['enabled'] = False
    config['ioc']['sync']['fetch'] = {'enabled': False, 'interval': '10s', 'sources': []}
    config['ioc']['sync']['publish'] = {'enabled': False, 'interval': '10s', 'targets': []}
    config['ioc']['sync']['endpoints'] = []
    for source_spec in config['ioc']['dataframe-import']['sources']:
        if source_spec['transport'] == 'local':
            source_spec['location'] = './var/import/inbox'
    config['ioc']['dataframe-import']['sources'] = [source for source in config['ioc']['dataframe-import']['sources'] if source['transport'] == 'local']
    config['logging'] = {'level': {'root': 'WARN'}}
    (root / 'application.yml').write_text(yaml.safe_dump(config, sort_keys=False, allow_unicode=True))
    shutil.copyfile(REPO / 'bootstrap/ioc-app/src/test/resources/logback-capacity.xml', root / 'logback.xml')
    return config


def live_heap_bytes(heap_info):
    # Serial GC reports new and tenured generations separately; G1 reports one heap.
    generations = re.findall(r'^\s*[^\n]*\btotal\s+\d+K, used\s+(\d+)K', heap_info, re.MULTILINE)
    if not generations:
        raise RuntimeError('Missing post-GC heap measurement')
    return sum(int(used) for used in generations) * 1024


def diagnostics(unit):
    result = {}
    for name, action in (('heap_before', 'GC.heap_info'), ('native', 'VM.native_memory summary'),
                         ('forced_gc', 'GC.run'), ('heap_after', 'GC.heap_info'), ('retained_histogram', 'GC.class_histogram')):
        result[name] = command(jdk_tool('jcmd') + [str(unit.pid), *action.split()], timeout=60)
    result['post_gc_live_heap_bytes'] = live_heap_bytes(result['heap_after'])
    result['post_gc_live_heap_gate'] = 'PASS' if result['post_gc_live_heap_bytes'] <= 256 * 1024**2 else 'FAIL'
    result['allocation_scope'] = 'Separate JFR sampled whole-JVM allocations; no exact all-thread or caller byte counter'
    return result


def sample(args, jar, report, index, evidence_root):
    unit, sampler, oracle, share = None, None, None, None
    result = {'index': index, 'status': 'RUNNING', 'started_epoch_seconds': time.time()}
    report['samples'].append(result)
    # State cleanup is unconditional, including exceptions and collection failure.
    temporary = tempfile.mkdtemp(prefix='ioc-cap6-', dir=REPO / '.dev')
    root = Path(temporary)
    try:
        config = private_config(args.config, root, args.port)
        if getattr(args, 'smb', False):
            original = yaml.safe_load(args.config.read_text())['ioc']
            config['ioc']['sync'] = original['sync']
            config['ioc']['dataframe-import']['sources'] += [item for item in original['dataframe-import']['sources'] if item['transport'] == 'smb']
            share = INPUTS.PrivateShare(config, args.environment, root, BASE, command)
            share.provision(config)
            (root / 'application.yml').write_text(yaml.safe_dump(config, sort_keys=False, allow_unicode=True))
            result['smb_namespace'] = share.namespace
        result['policy_sha256'] = BASE.digest(root / 'application.yml')
        result['policy_changes'] = ['Private state/inbox/import paths and loopback port',
                                    'Private SMB namespace' if share else 'Sync disabled for local reference', 'Log phase observer enabled']
        oracle = DiskOracle(config, root / 'oracle.db')
        unit = PrivateUnit(root)
        result['runtime'] = unit.start(jar, root / 'application.yml',
                                       environment=getattr(args, 'environment', None) if share else None, diagnostic=args.diagnostic)
        result['startup'] = ready(unit, args.port)
        sampler = ResourceSampler(unit.pid, unit.cgroup, root)
        warmup = root / 'warmup.html'
        fixture(warmup, 60, seed=1)
        feed_fixture(oracle, warmup)
        local_handoff(warmup, root)
        local = local_slices(unit, root, oracle, config, BASE.digest(warmup), args.timeout)
        verify_slices(local, oracle, root / 'var/db/ioc-dataframe.db')
        if args.initial_rows:
            initial = root / 'initial.html'
            result['initial_fixture'] = fixture(initial, args.initial_rows, seed=2)
            feed_fixture(oracle, initial)
            local_handoff(initial, root)
            local = local_slices(unit, root, oracle, config, BASE.digest(initial), args.timeout)
            verify_slices(local, oracle, root / 'var/db/ioc-dataframe.db')
        import_artifact = getattr(args, 'import_artifact', None)
        document = root / ('reference.csv' if import_artifact else 'reference.' + args.format)
        if import_artifact:
            result['input'] = INPUTS.import_fixture(document, args.rows, import_artifact, config, oracle, BASE.digest)
        elif getattr(args, 'document', None):
            shutil.copyfile(args.document, document)
            result['input'] = {'sha256': BASE.digest(document), 'bytes': document.stat().st_size, 'physical_input': str(args.document)}
            feed_fixture(oracle, document)
            if oracle.observations - 60 - args.initial_rows != args.rows:
                raise RuntimeError('Physical input occurrence count differs from declared workload')
        else:
            result['input'] = fixture(document, args.rows, args.shape)
            feed_fixture(oracle, document)
        result['expected_final_rows'] = oracle.counts()
        result['health_before'] = health(args.port, 'dataProcessingCapacity')['details']
        sampler.begin()
        handoff = share.handoff(document) if share else (INPUTS.import_handoff(document, root) if import_artifact else local_handoff(document, root))
        result['handoff'] = handoff
        local = local_slices(unit, root, oracle, config, BASE.digest(document), args.timeout,
                             result['input'] if import_artifact else None)
        quiet = quiet_seconds(config) if not import_artifact else 0
        result['local_window_upper_seconds'] = local['complete_monotonic'] - handoff['monotonic'] - quiet
        result['configured_stability_seconds'] = quiet
        result['raw_local_handoff_to_slices_seconds'] = local['complete_monotonic'] - handoff['monotonic']
        result['timeline'] = {key: value for key, value in local.items() if key != 'profiles'}
        result['resources'] = sampler.summary()
        result['health'] = health(args.port, 'dataProcessingCapacity')['details']
        result['canonical_oracle'] = oracle.check_database(root / 'var/db/ioc-dataframe.db')
        result['local_slices'] = verify_slices(local, oracle, root / 'var/db/ioc-dataframe.db')
        for artifact in config['ioc']['sink']['artifacts']:
            oracle.check_csv(artifact['name'], root / artifact['path'], root / 'var/db/ioc-dataframe.db', None)
        result['mutable_projection_oracle'] = 'PASS; fields, complete keys, canonical ids and durable generations'
        if share:
            result['publications'] = share.publications(unit, root, local, oracle, BASE, args.timeout)
            result['complete_smb_seconds'] = max(value['verified_monotonic'] for value in result['publications'].values()) - handoff['monotonic']
            result['smb_resource_window'] = sampler.summary()
        result['gates'] = gate_sample(result, args.rows)
        result['status'] = result['gates']['status']
        if args.diagnostic:
            result['diagnostics'] = diagnostics(unit)
    except Exception as failure:
        result.update(status='FAIL' if isinstance(failure, WorkloadFailure) else 'ERROR', failure=str(failure),
                      failure_kind='workload' if isinstance(failure, WorkloadFailure) else 'collection_or_execution')
        if sampler:
            try:
                result['resources_at_failure'] = sampler.summary()
            except Exception as collector_failure:
                result['collection_failure'] = str(collector_failure)
        if args.diagnostic and isinstance(failure, WorkloadFailure):
            try:
                result['diagnostics_after_failure'] = diagnostics(unit)
            except Exception as diagnostic_failure:
                result['diagnostic_failure'] = str(diagnostic_failure)
    finally:
        errors = []
        if sampler:
            try:
                sampler.close()
            except Exception as failure:
                errors.append(str(failure))
        if unit:
            try:
                result['cleanup'] = unit.close()
            except Exception as failure:
                errors.append(str(failure))
        if share:
            try:
                share.close()
                result['smb_namespace_removed'] = True
            except Exception as failure:
                errors.append(str(failure))
        if oracle:
            try:
                oracle.close()
            except Exception as failure:
                errors.append('Oracle close: ' + str(failure))
        # Keep small evidence and separate diagnostic recording, no SQLite/CSV copies.
        folder = evidence_root / str(index)
        folder.mkdir()
        for name in ('resources.jsonl', 'daemon.log', 'application.yml', 'diagnostic.jfr'):
            path = root / name
            if path.is_file():
                try:
                    shutil.copy2(path, folder / name)
                except Exception as failure:
                    errors.append('Evidence copy: ' + str(failure))
        if unit is None or result.get('cleanup', {}).get('process_terminated'):
            try:
                shutil.rmtree(root)
            except Exception as failure:
                errors.append('State removal: ' + str(failure))
        else:
            result['state_preserved_until_unit_termination'] = str(root)
        result['cleanup_errors'] = errors
        if errors:
            result['status'] = 'ERROR'
    result['temporary_state_removed'] = not root.exists()
    result['finished_epoch_seconds'] = time.time()
    save(evidence_root / 'report.json', report)
    print(json.dumps({'sample': index, 'status': result['status'], 'seconds': result.get('local_window_upper_seconds'),
                      'failure': result.get('failure'), 'gates': result.get('gates'), 'state_removed': result['temporary_state_removed']}), flush=True)
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jar', type=Path, required=True)
    parser.add_argument('--config', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True, help='New repo-local .dev evidence directory')
    parser.add_argument('--rows', type=int, default=100000)
    parser.add_argument('--format', choices=('html', 'docx'), default='html')
    parser.add_argument('--shape', choices=('mostly-unique', 'unique', 'collapse'), default='mostly-unique')
    parser.add_argument('--initial-rows', type=int, default=0)
    parser.add_argument('--samples', type=int, default=3)
    parser.add_argument('--timeout', type=int, default=900)
    parser.add_argument('--port', type=int, default=18206)
    parser.add_argument('--diagnostic', action='store_true')
    parser.add_argument('--import-artifact', choices=BASE.ARTIFACTS)
    parser.add_argument('--smb', action='store_true')
    parser.add_argument('--environment', type=Path, help='systemd EnvironmentFile with SMB credentials; never sourced')
    parser.add_argument('--document', type=Path, help='Existing physical HTML fixture with independently checked occurrence count')
    args = parser.parse_args()
    root = args.output.absolute()
    if args.rows < 1 or args.initial_rows < 0 or args.samples < 1 or args.timeout < 1:
        parser.error('Positive workload, samples and timeout required')
    if args.smb and (not args.environment or args.import_artifact):
        parser.error('SMB document reference requires EnvironmentFile and excludes local import mode')
    if args.document and (args.format != 'html' or args.import_artifact):
        parser.error('Physical fixture reference currently requires HTML document mode')
    if not root.is_relative_to(REPO / '.dev') or root.exists() or root.resolve() != root:
        parser.error('Use a new private repo-local .dev output without symlinks')
    if command(['git', '-C', str(REPO), 'status', '--porcelain']):
        parser.error('Commit the harness and policy before measuring')
    root.mkdir(parents=True)
    head = command(['git', '-C', str(REPO), 'rev-parse', 'HEAD'])
    report = {'source_commit': head, 'jar_sha256': BASE.digest(args.jar), 'driver_sha256': BASE.digest(__file__),
              'base_oracle_sha256': BASE.digest(BASE.__file__), 'policy_sha256': BASE.digest(args.config),
              'java': command(['java', '--version']), 'rows': args.rows, 'format': args.format, 'shape': args.shape,
              'input_driver_sha256': BASE.digest(INPUTS.__file__), 'import_artifact': args.import_artifact, 'smb': args.smb,
              'initial_rows': args.initial_rows, 'requested_samples': args.samples, 'mode': 'diagnostic' if args.diagnostic else 'primary',
              'samples': [], 'acceptance': 'NOT_ACCEPTED; exact Tlocal and complete G6 matrix are separate'}
    save(root / 'report.json', report)
    # Freeze packaged bytes once; a rebuild cannot alter later samples.
    with tempfile.TemporaryDirectory(prefix='ioc-cap6-runtime-', dir=REPO / '.dev') as temporary:
        jar = Path(temporary) / 'ioc-app.jar'
        shutil.copyfile(args.jar, jar)
        if BASE.digest(jar) != report['jar_sha256']:
            raise RuntimeError('Jar changed during freeze')
        for index in range(args.samples):
            if shutil.disk_usage(REPO / '.dev').free < 5 * 1024**3:
                report['failure'] = 'Less than 5 GiB free; no new JVM started'
                break
            value = sample(args, jar, report, index, root)
            if value['status'] != 'SCREEN_PASS':
                report['stopped_after_failed_screen'] = True
                break
    report['temporary_runtime_removed'] = not Path(temporary).exists()
    values = [value['local_window_upper_seconds'] for value in report['samples'] if value['status'] == 'SCREEN_PASS']
    if len(values) == args.samples:
        report['summary'] = {'n': len(values), 'median_upper_seconds': statistics.median(values), 'range_upper_seconds': [min(values), max(values)]}
        median_budget = 180 if args.rows >= 1000000 else 30
        report['status'] = 'SCREEN_PASS' if statistics.median(values) <= median_budget else 'FAIL'
    else:
        report['status'] = 'FAIL'
    if command(['git', '-C', str(REPO), 'rev-parse', 'HEAD']) != head or command(['git', '-C', str(REPO), 'status', '--porcelain']):
        report.update(status='ERROR', identity_failure='HEAD/worktree changed during qualification')
    save(root / 'report.json', report)
    if report['status'] != 'SCREEN_PASS':
        raise SystemExit(1)


if __name__ == '__main__':
    main()
