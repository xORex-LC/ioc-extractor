#!/usr/bin/env python3
"""Offline contract tests for immutable publication and recovery decisions."""
import importlib.util
import json
import os
import subprocess
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.dont_write_bytecode = True

SPEC = importlib.util.spec_from_file_location('publication', Path(__file__).parents[1] / 'ci/library-publication.py')
PUB = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(PUB)


class PublicationTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.bundle = Path(self.temp.name)
        self.version = '0.3.0-rc.1'
        self.folder = self.bundle / 'repository' / PUB.relative_path(self.version)
        self.folder.mkdir(parents=True)
        for suffix in PUB.SUFFIXES:
            (self.folder / (PUB.ARTIFACT + '-' + self.version + suffix)).write_bytes(b'fixture ' + suffix.encode())
        PUB.write_identity(self.bundle, dict(group=PUB.GROUP, artifact=PUB.ARTIFACT,
                                           version=self.version, commit='a' * 40, dirty=False, signed=False))

    def signed(self):
        metadata = PUB.read_bundle(self.bundle)
        for name in list(metadata['files']):
            path = self.bundle / 'repository' / name
            Path(str(path) + '.asc').write_bytes(b'fixture signature')
            for algorithm in ('md5', 'sha1', 'sha256'):
                Path(str(path) + '.' + algorithm).write_text(PUB.hashlib.new(algorithm, path.read_bytes()).hexdigest())
        metadata.update(signed=True, fingerprint=PUB.FINGERPRINT)
        PUB.write_identity(self.bundle, metadata)
        return PUB.read_bundle(self.bundle, signed=True)

    def test_real_gpg_signing_with_disposable_test_key(self):
        with tempfile.TemporaryDirectory(prefix='ioc-test-key-') as home:
            env = {**os.environ, 'GNUPGHOME': home}
            def gpg(*args, data=None):
                return subprocess.run(['gpg', '--batch', '--pinentry-mode', 'loopback',
                                       '--passphrase-fd', '0', *args], input=data or b'test-passphrase',
                                      capture_output=True, check=True, timeout=30, env=env).stdout
            try:
                gpg('--quick-generate-key', 'Publication contract test', 'ed25519', 'sign', '1d')
                listing = gpg('--with-colons', '--list-secret-keys', '--fingerprint').decode()
                fingerprint = next(line.split(':')[9] for line in listing.splitlines() if line.startswith('fpr:'))
                private_key = gpg('--armor', '--export-secret-keys', fingerprint).decode()
                with patch.object(PUB, 'FINGERPRINT', fingerprint), patch.dict(os.environ, {
                        'GPG_PRIVATE_KEY': private_key, 'GPG_PASSPHRASE': 'test-passphrase'}):
                    PUB.sign(self.bundle)
                    metadata = PUB.read_bundle(self.bundle, signed=True)
                    self.assertEqual(len(metadata['files']), 20)
                    for name in metadata['files']:
                        if name.endswith('.asc'):
                            gpg('--verify', str(self.bundle / 'repository' / name),
                                str(self.bundle / 'repository' / name.removesuffix('.asc')))
            finally:
                subprocess.run(['gpgconf', '--kill', 'gpg-agent'], capture_output=True, env=env, timeout=10)

    def test_bundle_detects_changed_bytes(self):
        next(self.folder.glob('*.jar')).write_bytes(b'changed')
        with self.assertRaisesRegex(ValueError, 'checksum mismatch'):
            PUB.read_bundle(self.bundle)

    def test_bundle_rejects_extra_file(self):
        (self.folder / 'extra').write_text('not in manifest')
        with self.assertRaisesRegex(ValueError, 'unmanifested'):
            PUB.read_bundle(self.bundle)

    def test_bundle_rejects_path_escape(self):
        metadata = PUB.read_bundle(self.bundle)
        metadata['files'] = {'../../outside': 'a' * 64}
        (self.bundle / 'identity.json').write_text(json.dumps(metadata))
        with self.assertRaisesRegex(ValueError, 'artifact path'):
            PUB.read_bundle(self.bundle)

    def test_bundle_rejects_symlink(self):
        path = next(self.folder.glob('*.jar'))
        content = path.read_bytes()
        path.unlink()
        target = self.bundle / 'outside'
        target.write_bytes(content)
        path.symlink_to(target)
        with self.assertRaisesRegex(ValueError, 'symbolic artifact'):
            PUB.read_bundle(self.bundle)

    def test_unsigned_bundle_cannot_publish(self):
        with self.assertRaisesRegex(ValueError, 'signed bundle'):
            PUB.read_bundle(self.bundle, signed=True)

    def test_dirty_checkout_cannot_be_signed(self):
        metadata = PUB.read_bundle(self.bundle)
        metadata['dirty'] = True
        PUB.write_identity(self.bundle, metadata)
        with self.assertRaisesRegex(ValueError, 'clean source'):
            PUB.sign(self.bundle)

    def test_snapshot_is_not_release(self):
        with self.assertRaisesRegex(ValueError, 'snapshot'):
            PUB.version_check('0.3.0-SNAPSHOT', release=True)

    def test_remote_conflict_aborts_before_any_upload(self):
        self.signed()
        with patch.object(PUB, 'authorization', return_value={}), patch.object(PUB, 'request', return_value=b'conflict') as request:
            with self.assertRaisesRegex(ValueError, 'differs'):
                PUB.publish_github(self.bundle)
            self.assertEqual(request.call_count, 1)
            self.assertNotIn('PUT', request.call_args.args)

    def test_github_retry_writes_only_missing_file(self):
        metadata = self.signed()
        missing = next(name for name in metadata['files'] if name.endswith('-sources.jar'))
        writes = []
        def remote(url, method='GET', data=None, headers=None, missing_ok=False):
            name = url.removeprefix(PUB.GITHUB + '/')
            if method == 'PUT':
                writes.append((name, data))
                return b''
            if name == missing and not writes:
                return None
            content = (self.bundle / 'repository' / name).read_bytes()
            return content + b'\n' if name.endswith(('.md5', '.sha1', '.sha256')) else content
        with patch.object(PUB, 'authorization', return_value={}), patch.object(PUB, 'request', side_effect=remote):
            PUB.publish_github(self.bundle)
        self.assertEqual(writes, [(missing, (self.bundle / 'repository' / missing).read_bytes())])

    def test_partial_central_visibility_does_not_create_deployment(self):
        self.signed()
        with patch.object(PUB, 'compare_remote', return_value=['one']), patch.object(PUB, 'request') as request:
            with self.assertRaisesRegex(ValueError, 'partial'):
                PUB.publish_central(self.bundle, False, '')
            request.assert_not_called()

    def test_central_resume_validates_without_reupload_or_release(self):
        metadata = self.signed()
        deployment_id = '12345678-1234-1234-1234-123456789abc'
        (self.bundle / 'central-deployment-id.txt').write_text(deployment_id)
        name = PUB.ARTIFACT + '-' + self.version + '-' + PUB.digest((self.bundle / 'identity.json').read_bytes())
        status = json.dumps(dict(deploymentName=name, deploymentState='VALIDATED')).encode()
        with patch.object(PUB, 'compare_remote', return_value=list(metadata['files'])), patch.object(PUB, 'authorization', return_value={}), patch.object(PUB, 'request', return_value=status) as request:
            PUB.publish_central(self.bundle, False, '')
            self.assertEqual(request.call_count, 1)
            self.assertIn('/status?id=' + deployment_id, request.call_args.args[0])

    def test_central_rejects_other_bundle_deployment(self):
        metadata = self.signed()
        with patch.object(PUB, 'compare_remote', return_value=list(metadata['files'])), patch.object(PUB, 'authorization', return_value={}), patch.object(PUB, 'request', return_value=b'{"deploymentName":"other"}'):
            with self.assertRaisesRegex(ValueError, 'does not belong'):
                PUB.publish_central(self.bundle, True, '12345678-1234-1234-1234-123456789abc')


if __name__ == '__main__':
    unittest.main()
