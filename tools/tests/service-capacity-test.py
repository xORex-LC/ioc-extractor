#!/usr/bin/env python3
"""Offline contracts for bounded CAP-6 oracles, resource accounting and fail-closed evidence."""
import importlib.util
import json
from pathlib import Path
import sqlite3
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.dont_write_bytecode = True
SPEC = importlib.util.spec_from_file_location('service_capacity', Path(__file__).resolve().parents[1] / 'dev/service-capacity.py')
CAP = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CAP)
UPGRADE_SPEC = importlib.util.spec_from_file_location('capacity_upgrade', Path(__file__).resolve().parents[1] / 'dev/service-capacity-upgrade.py')
UPGRADE = importlib.util.module_from_spec(UPGRADE_SPEC)
UPGRADE_SPEC.loader.exec_module(UPGRADE)


def policy():
    columns = {'masks': ['mask', 'url_match', 'host_match', 'source'],
               'ip_list': ['ip', 'score', 'source'], 'hashes': ['hash_md5', 'hash_sha256', 'hash_sha1', 'source'],
               'address_blacklist': ['forbidden_url', 'forbidden_ip'],
               'ioc_aggregate': ['name', 'ip_address', 'url_match', 'host_match', 'hash']}
    keys = {'masks': ['mask'], 'ip_list': ['ip', 'score'], 'hashes': ['hash_md5', 'hash_sha256', 'hash_sha1'],
            'address_blacklist': columns['address_blacklist'], 'ioc_aggregate': columns['ioc_aggregate'][1:]}
    return {'ioc': {'refang': {'rules': [{'from': 'hxxps', 'to': 'https'}, {'from': '[.]', 'to': '.'}, {'from': '[:]', 'to': ':'}]},
                    'sink': {'artifacts': [{'name': name, 'columns': [{'name': column} for column in
                                (['id'] if name in ('masks', 'ip_list', 'hashes') else []) + names]}
                               for name, names in columns.items()]},
                    'artifact-identity': {'artifacts': [{'name': name, 'key-columns': names} for name, names in keys.items()]}}}


class ServiceCapacityTest(unittest.TestCase):
    def test_upgrade_startup_failure_stops_unit_before_state_removal(self):
        from types import SimpleNamespace
        from unittest.mock import MagicMock
        with tempfile.TemporaryDirectory() as temporary:
            repo = Path(temporary)
            (repo / '.dev').mkdir()
            config = policy()
            config['ioc']['processing'] = {}
            source = repo / 'config.yml'
            source.write_text(CAP.yaml.safe_dump(config))
            jar = repo / 'runtime.jar'
            jar.write_text('frozen executable')
            args = SimpleNamespace(previous=jar, candidate=jar, config=source, output=repo / '.dev/evidence')
            unit = MagicMock()
            def start(executable, policy):
                self.assertEqual(policy.name, 'previous.yml')
                self.assertFalse((policy.parent / 'application.yml').exists())
                self.assertTrue((policy.parent / 'candidate.yml').is_file())
                self.assertNotIn('workspace', CAP.yaml.safe_load(policy.read_text())['ioc']['processing'])
                return {}
            unit.start.side_effect = start
            def stop():
                root = next((repo / '.dev').glob('ioc-cap6-upgrade-*'))
                self.assertTrue((root / 'candidate.jar').exists())
                self.assertTrue((root / 'oracle.db').exists())
                (root / 'daemon.log').write_text('startup failure evidence')
                return {'process_terminated': True}
            unit.close.side_effect = stop
            def private(source, root, port):
                (root / 'application.yml').write_text(source.read_text())
                return config
            def git(arguments):
                return 'head' if 'rev-parse' in arguments else ''
            with patch.object(UPGRADE.CAP, 'REPO', repo), patch.object(UPGRADE.CAP, 'command', side_effect=git), \
                    patch.object(UPGRADE.CAP, 'private_config', side_effect=private), \
                    patch.object(UPGRADE.CAP, 'PrivateUnit', return_value=unit), \
                    patch.object(UPGRADE.CAP, 'ready', side_effect=RuntimeError('readiness failure')):
                report = UPGRADE.rehearse(args)
            self.assertEqual(report['status'], 'ERROR')
            self.assertTrue(report['temporary_state_removed'])
            unit.close.assert_called_once()
            self.assertEqual(list((repo / '.dev').glob('ioc-cap6-upgrade-*')), [])
            self.assertTrue((args.output / 'report.json').is_file())
            self.assertEqual((args.output / 'previous-failure.log').read_text(), 'startup failure evidence')

    def test_stopped_backup_hashes_detect_journal_or_output_mutation(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            path = root / 'db-journal'
            path.write_bytes(b'original')
            before = UPGRADE.snapshot_files(root)
            path.write_bytes(b'changed')
            self.assertNotEqual(before, UPGRADE.snapshot_files(root))

    def test_post_gc_heap_includes_both_serial_generations(self):
        serial = ' def new generation total 62464K, used 44K\n tenured generation total 138564K, used 49494K\n Metaspace used 86737K'
        self.assertEqual(CAP.live_heap_bytes(serial), 49538 * 1024)
        self.assertEqual(CAP.live_heap_bytes(' garbage-first heap total 131072K, used 42K'), 42 * 1024)
        with self.assertRaisesRegex(RuntimeError, 'Missing post-GC'):
            CAP.live_heap_bytes('unavailable')

    def test_window_maximum_does_not_reuse_population_maximum(self):
        before = {'PROMOTION': {'completed': 10, 'totalHoldNanos': 25000000000, 'maximumHoldNanos': 13000000000,
                               'totalWaitNanos': 100, 'maximumWaitNanos': 50}}
        after = {'PROMOTION': dict(before['PROMOTION'], completed=15, totalHoldNanos=26500000000)}
        value = CAP.writer_window(before, after)['PROMOTION']
        self.assertEqual(value['maximumHoldNanosUpperBound'], 1500000000)
        self.assertFalse(value['maximumHoldNanosIsExact'])
        after['PROMOTION'].update(totalHoldNanos=40000000000, maximumHoldNanos=15000000000)
        self.assertTrue(CAP.writer_window(before, after)['PROMOTION']['maximumHoldNanosIsExact'])
        after['PROMOTION']['completed'] = 9
        with self.assertRaisesRegex(RuntimeError, 'regressed'):
            CAP.writer_window(before, after)

    def test_absolute_operator_paths_are_privatized_without_changing_limits(self):
        config = next(CAP.yaml.safe_load_all((CAP.REPO / 'bootstrap/ioc-app/src/main/resources/application.yml').read_text()))
        ioc = config['ioc']
        ioc['ingestion']['ledger']['path'] = '/srv/shared/ledger'
        ioc['processing']['workspace']['directory'] = '/srv/shared/workspace'
        ioc['sink']['artifacts'][0]['path'] = '/srv/shared/operator.csv'
        limits = dict(ioc['processing']['workspace'])
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source = root / 'input.yml'
            source.write_text(CAP.yaml.safe_dump(config))
            private = CAP.private_config(source, root, 18206)['ioc']
            self.assertEqual(private['ingestion']['ledger']['path'], './var/ledger')
            self.assertEqual(private['sink']['artifacts'][0]['path'], './dataframe/operator.csv')
            self.assertEqual({key: value for key, value in private['processing']['workspace'].items() if key != 'directory'},
                             {key: value for key, value in limits.items() if key != 'directory'})
            self.assertTrue(all(value.startswith('./var/import/') for value in private['dataframe-import']['runtime']['dirs'].values()))

    def test_missing_diagnostic_launcher_uses_the_current_jdk_modules(self):
        with patch.object(CAP.shutil, 'which', side_effect=lambda name: '/usr/bin/java' if name == 'java' else None):
            self.assertEqual(CAP.jdk_tool('jstat'), ['/usr/bin/java', '-m', 'jdk.jcmd/sun.tools.jstat.Jstat'])

    def test_jvm_counters_use_bytes_without_double_counting_class_space(self):
        header = 'S0U S1U EU OU S0C S1C EC OC MU MC CCSU CCSC YGC FGC GCT CGC CGCT'
        with patch.object(CAP, 'command', return_value=header + '\n1 2 3 4 10 20 30 40 50 60 7 8 9 0 1.5 - -'):
            value = CAP.jvm_sample(42)
            self.assertEqual(value['heap_used_bytes'], 10 * 1024)
            self.assertEqual(value['heap_committed_bytes'], 100 * 1024)
            self.assertEqual(value['metaspace_used_bytes'], 50 * 1024)
            self.assertEqual(value['gc']['GCT'], 1.5)
        with patch.object(CAP, 'command', return_value='EU OU\n1 2'):
            with self.assertRaisesRegex(RuntimeError, 'Missing JDK'):
                CAP.jvm_sample(42)

    def test_all_five_import_fixtures_preserve_case_paths_and_duplicate_slot_hole(self):
        config = policy()
        config['ioc']['dataframe-import'] = {'contracts': [
            {'id': spec['name'], 'mode': 'as-is', 'artifacts': [{'name': spec['name']}],
             'recognition': {'required-columns': [item['name'] for item in spec['columns']]}}
            for spec in config['ioc']['sink']['artifacts']]}
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            oracle = CAP.DiskOracle(config, root / 'oracle.db')
            try:
                for artifact in CAP.BASE.ARTIFACTS:
                    path = root / (artifact + '.csv')
                    manifest = CAP.INPUTS.import_fixture(path, 10, artifact, config, oracle, CAP.BASE.digest)
                    self.assertEqual(manifest['accepted_rows'], 10)
                    self.assertEqual(oracle.counts()[artifact], 10)
                self.assertIn('https://Preserved-1.Example.test:9090/api?q=1', (root / 'masks.csv').read_text())
                rows = (root / 'ip_list.csv').read_text().splitlines()
                self.assertTrue(rows[-1].startswith('900011;'))
                self.assertIn('Ignored duplicate', rows[-1])
                kept = [json.loads(row[0]) for row in oracle.db.execute("SELECT fields FROM expected WHERE artifact='ip_list'")]
                self.assertEqual({row['source'] for row in kept}, {'Original Case'})
            finally:
                oracle.close()

    def test_html_and_real_docx_have_equal_fields_keys_and_winners(self):
        with tempfile.TemporaryDirectory() as temporary:
            root, outputs = Path(temporary), []
            for suffix in ('html', 'docx'):
                path = root / ('fixture.' + suffix)
                manifest = CAP.fixture(path, 1000)
                oracle = CAP.DiskOracle(policy(), root / (suffix + '.db'))
                try:
                    CAP.feed_fixture(oracle, path)
                    self.assertEqual(oracle.observations, 1000)
                    self.assertEqual(manifest['sections'], 4)
                    outputs.append(list(oracle.db.execute('SELECT * FROM expected ORDER BY artifact,key')))
                    self.assertGreater(oracle.counts()['ioc_aggregate'], 500)
                finally:
                    oracle.close()
            self.assertEqual(outputs[0], outputs[1])

    def test_keep_first_and_last_nonempty_complete_keys_are_independent(self):
        with tempfile.TemporaryDirectory() as temporary:
            oracle = CAP.DiskOracle(policy(), Path(temporary) / 'oracle.db')
            try:
                oracle.source_key = 'document-sha'
                oracle.add('ip_list', {'ip': '192.0.2.1', 'source': 'first'})
                oracle.add('ip_list', {'ip': '192.0.2.1', 'source': 'last'})
                oracle.add('ip_list', {'ip': '192.0.2.1', 'score': '20', 'source': 'distinct'})
                oracle.add('ioc_aggregate', {'host_match': 'example.org', 'name': 'first'}, last=True)
                oracle.add('ioc_aggregate', {'host_match': 'example.org', 'name': 'last'}, last=True)
                rows = [json.loads(row[0]) for row in oracle.db.execute("SELECT fields FROM expected WHERE artifact='ip_list'")]
                self.assertEqual({row['source'] for row in rows}, {'first', 'distinct'})
                aggregate = json.loads(oracle.db.execute("SELECT fields FROM expected WHERE artifact='ioc_aggregate'").fetchone()[0])
                self.assertEqual(aggregate['name'], 'last')
            finally:
                oracle.close()

    def test_streamed_csv_rejects_corruption_missing_rows_duplicates_and_slot_changes(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            oracle = CAP.DiskOracle(policy(), root / 'oracle.db')
            try:
                oracle.source_key = 'doc'
                oracle.add('ip_list', {'ip': '192.0.2.1', 'source': 'first'})
                key = oracle.db.execute("SELECT key FROM expected WHERE artifact='ip_list'").fetchone()[0]
                with sqlite3.connect(root / 'actual.db') as actual:
                    actual.executescript('CREATE TABLE ip_list(row_key TEXT,_lifecycle_id INTEGER);'
                        'CREATE TABLE export_slot_assignment(profile TEXT,artifact TEXT,lifecycle_id INTEGER,slot INTEGER);')
                    actual.execute('INSERT INTO ip_list VALUES(?,1)', (key,))
                    actual.execute("INSERT INTO export_slot_assignment VALUES('p','ip_list',1,60)")
                valid = 'id;ip;score;source\n60;192.0.2.1;NULL;first\n'
                path = root / 'public.csv'
                path.write_text(valid)
                oracle.check_csv('ip_list', path, root / 'actual.db', 'p')
                for value, error in ((valid.replace('first', 'wrong'), 'fields/key'),
                                     ('id;ip;score;source\n', 'missing rows'),
                                     ('ip;score;source\n192.0.2.1;NULL;first\n', 'schema'),
                                     ('id;source;score;ip\n60;first;NULL;192.0.2.1\n', 'schema'),
                                     (valid + valid.splitlines()[1] + '\n', 'Duplicate'),
                                     (valid.replace('60;', '61;'), 'slot/registry')):
                    path.write_text(value)
                    with self.assertRaisesRegex(RuntimeError, error):
                        oracle.check_csv('ip_list', path, root / 'actual.db', 'p')
            finally:
                oracle.close()

    def test_microsecond_psi_counters_and_regression_rejection(self):
        before = CAP.pressure('some avg10=0.00 total=100\nfull avg10=0.00 total=10')
        after = CAP.pressure('some avg10=0.00 total=200\nfull avg10=0.00 total=60')
        self.assertEqual(CAP.difference(before, after), {'some': 100, 'full': 50})
        with self.assertRaisesRegex(RuntimeError, 'regressed'):
            CAP.difference(after, before)
        with self.assertRaisesRegex(RuntimeError, 'Missing'):
            CAP.difference(before, {'some': 100})

    def test_fast_completion_cannot_hide_long_writer_hold_or_oom(self):
        sample = {'local_window_upper_seconds': 1, 'resources': {'peaks': {'rss_bytes': 100, 'non_file_bytes': 100},
                   'psi_full_fraction': 0, 'memory_events_delta': {'oom': 0, 'oom_kill': 0}},
                  'health_before': {'writerOperations': {}},
                  'health': {'writerOperations': {'PROMOTION': {'completed': 1, 'maximumHoldNanos': 6000000000,
                    'totalHoldNanos': 6000000000, 'maximumWaitNanos': 0, 'totalWaitNanos': 0}}}}
        self.assertEqual(CAP.gate_sample(sample, 100000)['violations'], ['writer_hold'])
        sample['resources']['memory_events_delta']['oom_kill'] = 1
        self.assertIn('oom', CAP.gate_sample(sample, 100000)['violations'])

    def test_failed_sampler_cannot_publish_partial_success(self):
        with tempfile.TemporaryDirectory() as temporary:
            with patch.object(CAP.ResourceSampler, 'read', side_effect=[{'monotonic': 1}, OSError('synthetic failure')]):
                sampler = CAP.ResourceSampler(1, Path(temporary), Path(temporary))
                sampler.worker.join(2)
                self.assertFalse(sampler.worker.is_alive())
                with self.assertRaisesRegex(RuntimeError, 'sampler failed'):
                    sampler.summary()
                with self.assertRaisesRegex(RuntimeError, 'sampler failed'):
                    sampler.close()

    def test_atomic_handoff_uses_completion_anchor_not_old_input_mtime(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / 'var/inbox').mkdir(parents=True)
            source = root / 'input.html'
            source.write_text('fixture')
            import os
            os.utime(source, (1, 1))
            anchor = CAP.local_handoff(source, root)
            self.assertGreater(anchor['epoch_seconds'], 1)
            self.assertEqual((root / 'var/inbox/input.html').read_text(), 'fixture')
            self.assertFalse((root / 'var/inbox/input.html.part').exists())

    def test_startup_failure_stops_owned_unit_before_removing_private_state(self):
        from types import SimpleNamespace
        from unittest.mock import MagicMock
        with tempfile.TemporaryDirectory() as temporary:
            repo = Path(temporary)
            (repo / '.dev').mkdir()
            evidence = repo / 'evidence'
            evidence.mkdir()
            args = SimpleNamespace(config=repo / 'policy.yml', port=18206, diagnostic=False)
            args.config.write_text('policy')
            unit = MagicMock()
            unit.start.return_value = {}
            order = []
            def stop():
                state = next((repo / '.dev').iterdir())
                self.assertTrue((state / 'oracle.db').exists())
                order.append('stopped')
                return {'process_terminated': True}
            unit.close.side_effect = stop
            with patch.object(CAP, 'REPO', repo), patch.object(CAP, 'PrivateUnit', return_value=unit), \
                    patch.object(CAP, 'private_config', return_value=policy()), \
                    patch.object(CAP.BASE, 'digest', return_value='sha'), \
                    patch.object(CAP, 'ready', side_effect=RuntimeError('readiness failure')):
                report = {'samples': []}
                value = CAP.sample(args, repo / 'jar', report, 0, evidence)
            self.assertEqual(order, ['stopped'])
            self.assertEqual(value['status'], 'ERROR')
            self.assertTrue(value['temporary_state_removed'])
            self.assertEqual(list((repo / '.dev').iterdir()), [])
            self.assertEqual(json.loads((evidence / 'report.json').read_text())['samples'][0]['failure'], 'readiness failure')

    def test_file_ledger_terminal_anchor_preserves_the_configured_journal_backend(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            directory = root / 'var/ledger/document-admission'
            directory.mkdir(parents=True)
            (directory / 'fixture.properties').write_text('#ioc document admission\n'
                'sourceKey=sha\nobservationId=occurrence\nphase=TERMINAL\n'
                'createdAt=2026-10-07T13\\:52\\:21.331827039Z\n'
                'updatedAt=2026-10-07T13\\:52\\:22.123Z\n'
                'terminalOutcome=SUCCEEDED\nregistrationFinalized=true\n')
            config = {'ioc': {'ingestion': {'ledger': {'type': 'file', 'path': './var/ledger'}}}}
            rows = CAP.admission_rows(root, config, 'sha')
            self.assertEqual(rows[0]['registration_finalized'], 1)
            self.assertEqual(rows[0]['terminal_outcome'], 'SUCCEEDED')
            self.assertGreater(rows[0]['updated_at_ms'], rows[0]['created_at_ms'])
            self.assertEqual(CAP.admission_rows(root, config, 'other'), [])

    def test_worker_oom_fails_even_when_the_jvm_unit_is_active(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            unit = CAP.PrivateUnit(root)
            log = root / 'daemon.log'
            log.write_text('unrelated startup\njava.lang.OutOf')
            with patch.object(unit, 'properties', return_value={'ActiveState': 'active'}):
                unit.assert_running()
                with log.open('a') as contents:
                    contents.write('MemoryError: Java heap space\n')
                with self.assertRaisesRegex(CAP.WorkloadFailure, 'OutOfMemoryError'):
                    unit.assert_running()

    def test_workspace_full_stops_the_failed_capacity_screen_before_retry(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / 'daemon.log').write_text('org.sqlite.SQLiteException: [SQLITE_FULL] database is full')
            with self.assertRaisesRegex(CAP.WorkloadFailure, 'capacity exhausted'):
                CAP.PrivateUnit(root).assert_running()

    def test_evidence_copy_failure_still_removes_terminated_private_state(self):
        from types import SimpleNamespace
        from unittest.mock import MagicMock
        with tempfile.TemporaryDirectory() as temporary:
            repo = Path(temporary)
            (repo / '.dev').mkdir()
            evidence = repo / 'evidence'
            evidence.mkdir()
            args = SimpleNamespace(config=repo / 'policy.yml', port=18206, diagnostic=False)
            unit = MagicMock()
            unit.start.return_value = {}
            def stop():
                state = next((repo / '.dev').iterdir())
                (state / 'daemon.log').write_text('failure evidence')
                return {'process_terminated': True}
            unit.close.side_effect = stop
            with patch.object(CAP, 'REPO', repo), patch.object(CAP, 'PrivateUnit', return_value=unit), \
                    patch.object(CAP, 'private_config', return_value=policy()), \
                    patch.object(CAP.BASE, 'digest', return_value='sha'), \
                    patch.object(CAP, 'ready', side_effect=RuntimeError('readiness failure')), \
                    patch.object(CAP.shutil, 'copy2', side_effect=OSError('disk full')):
                value = CAP.sample(args, repo / 'jar', {'samples': []}, 0, evidence)
            self.assertTrue(value['temporary_state_removed'])
            self.assertEqual(value['status'], 'ERROR')
            self.assertIn('Evidence copy: disk full', value['cleanup_errors'])
            self.assertTrue((evidence / 'report.json').is_file())


if __name__ == '__main__':
    unittest.main()
