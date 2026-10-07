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


def policy():
    columns = {'masks': ['mask', 'url_match', 'host_match', 'source'],
               'ip_list': ['ip', 'score', 'source'], 'hashes': ['hash_md5', 'hash_sha256', 'hash_sha1', 'source'],
               'address_blacklist': ['forbidden_url', 'forbidden_ip'],
               'ioc_aggregate': ['name', 'ip_address', 'url_match', 'host_match', 'hash']}
    keys = {'masks': ['mask'], 'ip_list': ['ip', 'score'], 'hashes': ['hash_md5', 'hash_sha256', 'hash_sha1'],
            'address_blacklist': columns['address_blacklist'], 'ioc_aggregate': columns['ioc_aggregate'][1:]}
    return {'ioc': {'refang': {'rules': [{'from': 'hxxps', 'to': 'https'}, {'from': '[.]', 'to': '.'}, {'from': '[:]', 'to': ':'}]},
                    'sink': {'artifacts': [{'name': name, 'columns': [{'name': column} for column in names]} for name, names in columns.items()]},
                    'artifact-identity': {'artifacts': [{'name': name, 'key-columns': names} for name, names in keys.items()]}}}


class ServiceCapacityTest(unittest.TestCase):
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
                  'health': {'writerOperations': {'PROMOTION': {'maximumHoldNanos': 6000000000}}}}
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


if __name__ == '__main__':
    unittest.main()
