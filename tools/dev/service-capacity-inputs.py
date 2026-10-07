#!/usr/bin/env python3
"""Public-path CAP-6 import fixtures and owned SMB namespaces; no canonical business SQL."""
import csv
import hashlib
import json
from pathlib import Path
import shutil
import time
import uuid


def import_fixture(path, count, artifact, config, oracle, digest):
    contracts = [item for item in config['ioc']['dataframe-import']['contracts']
                 if [entry['name'] for entry in item['artifacts']] == [artifact]]
    if len(contracts) != 1 or contracts[0]['mode'] != 'as-is':
        raise ValueError('One AS_IS target-only contract required')
    contract = contracts[0]
    header = contract['recognition']['required-columns']
    source = 'dataframe-import:trusted-local'
    oracle.source_key = source
    with path.open('w', newline='') as contents:
        writer = csv.writer(contents, delimiter=';', lineterminator='\n')
        writer.writerow(header)
        first = None
        for number in range(1, count + 1):
            url = f'https://Preserved-{number}.Example.test:9090/api?q=1'
            value = {
                'masks': {'mask': url, 'url_match': 'u:hEX,dEX', 'source': 'Original Case'},
                'ip_list': {'ip': '192.' + '.'.join(str(number >> shift & 255) for shift in (16, 8, 0)),
                            'score': '20', 'source': 'Original Case', 'description': 'Unchanged'},
                'hashes': {'hash_md5': hashlib.md5(f'import:{number}'.encode()).hexdigest(), 'source': 'Original Case'},
                'address_blacklist': {'forbidden_url': url},
                'ioc_aggregate': {'name': 'Original Case', 'url_match': url, 'host_match': f'Preserved-{number}.Example.test'}
            }[artifact]
            value['id'] = str(900000 + number)
            writer.writerow([value.get(name, 'NULL') for name in header])
            oracle.add(artifact, value)
            if first is None:
                first = dict(value)
        if artifact == 'ip_list':
            first.update(id=str(900001 + count), source='Ignored duplicate', description='Ignored')
            writer.writerow([first.get(name, 'NULL') for name in header])
    oracle.db.commit()
    return {'artifact': artifact, 'accepted_rows': count, 'duplicate_rows': int(artifact == 'ip_list'),
            'source_rows': count + int(artifact == 'ip_list'), 'has_slots': 'id' in header,
            'sha256': digest(path), 'bytes': path.stat().st_size, 'contract_id': contract['id'],
            'source_key': source, 'mode': 'as-is', 'case_and_path_preserved': True}


def import_handoff(path, root):
    target = root / 'var/import/inbox' / path.name
    target.parent.mkdir(parents=True, exist_ok=True)
    staging = target.with_suffix('.csv.part')
    shutil.copyfile(path, staging)
    staging.touch()
    staging.replace(target)
    return {'monotonic': time.monotonic(), 'epoch_seconds': time.time(), 'kind': 'producer atomic import rename completed'}


class PrivateShare:
    """Provision and delete only an unpredictable namespace owned by this sample."""
    def __init__(self, config, environment_file, root, base, command):
        script = 'import os,json; print(json.dumps({k:os.environ[k] for k in ("SMB_USER","SMB_PASSWORD","SMB_DOMAIN") if k in os.environ}))'
        # Output remains in this Python process; credentials never enter reports or argv.
        environment = json.loads(command(['systemd-run', '--user', '--quiet', '--wait', '--pipe',
            '-p', 'EnvironmentFile=' + str(environment_file), shutil.which('python3'), '-c', script]))
        endpoint = config['ioc']['sync']['endpoints'][0]['smb']
        if endpoint['username'] != '${SMB_USER}' or endpoint['password'] != '${SMB_PASSWORD}':
            raise ValueError('SMB credential placeholders required')
        self.share = base.Share(endpoint, environment, root)
        self.namespace = '.ioc-cap6-' + uuid.uuid4().hex
        self.owned = False

    def provision(self, config):
        folders = ['', '/send', '/get', '/import', '/import/.ioc-managed-import']
        folders += ['/import/.ioc-managed-import/' + part for part in ('processing', 'terminal', 'quarantine', 'probe')]
        for folder in folders:
            self.share.run('mkdir "' + self.namespace + folder + '"')
            self.owned = True
        for source in config['ioc']['sync']['fetch']['sources']:
            source['remote-path'] = '/' + self.namespace + '/send'
        for target in config['ioc']['sync']['publish']['targets']:
            target['remote-path'] = '/' + self.namespace + '/get'
        for source in config['ioc']['dataframe-import']['sources']:
            if source['transport'] == 'smb':
                source['location'] = self.namespace + '/import'

    def handoff(self, path):
        remote = self.namespace + '/send/' + path.name
        upload_start = time.monotonic()
        self.share.run(f'put "{path}" "{remote}.part"')
        upload_complete = time.monotonic()
        self.share.run(f'rename "{remote}.part" "{remote}"')
        return {'monotonic': time.monotonic(), 'epoch_seconds': time.time(),
                'kind': 'producer SMB atomic rename completed',
                'upload_seconds': upload_complete - upload_start,
                'rename_request_seconds': time.monotonic() - upload_complete}

    def publications(self, unit, root, local, oracle, base, timeout):
        deadline, found = time.monotonic() + timeout, {}
        while time.monotonic() < deadline:
            unit.assert_running()
            for profile, expected in local['profiles'].items():
                if profile in found:
                    continue
                rows = base.rows(root / 'var/db/ioc-service.db',
                    'SELECT * FROM publish_ledger WHERE profile=? AND slice_id=? AND status=?',
                    (profile, expected['run']['run_id'], 'SUCCEEDED'))
                if not rows:
                    continue
                publication = rows[0]
                folder = publication['remote_path']
                if not folder.lstrip('/').startswith(self.namespace + '/get/'):
                    raise RuntimeError('Publication escaped private SMB namespace')
                target = root / 'readback' / profile
                target.mkdir(parents=True, exist_ok=True)
                for name in ('_SUCCESS', 'manifest.json'):
                    self.share.run(f'get "{folder}/{name}" "{target / name}"')
                marker = (target / '_SUCCESS').read_text().strip()
                if marker != expected['run']['manifest_sha256'] or base.digest(target / 'manifest.json') != marker:
                    raise RuntimeError('Remote marker/manifest mismatch')
                manifest = json.loads((target / 'manifest.json').read_text())
                if manifest != expected['manifest']:
                    raise RuntimeError('Remote manifest/revision coverage mismatch')
                for entry in manifest['artifacts']:
                    if Path(entry['file']).name != entry['file']:
                        raise RuntimeError('Unsafe remote manifest filename')
                    path = target / entry['file']
                    self.share.run(f'get "{folder}/{entry["file"]}" "{path}"')
                    if base.digest(path) != entry['sha256'] or entry['rows'] != oracle.counts()[entry['artifact']]:
                        raise RuntimeError('Remote CSV hash/count mismatch')
                    oracle.check_csv(entry['artifact'], path, root / 'var/db/ioc-dataframe.db', profile)
                found[profile] = {'verified_monotonic': time.monotonic(), 'slice_id': publication['slice_id'],
                                  'manifest_sha256': marker, 'status': publication['status']}
            if set(found) == set(local['profiles']):
                return found
            time.sleep(.2)
        raise RuntimeError('Complete remote publication timeout')

    def close(self):
        try:
            if self.owned:
                self.share.run('deltree "' + self.namespace + '"')
        finally:
            self.share.close()
