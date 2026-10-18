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
import zipfile
from unittest.mock import patch
import urllib.error
import urllib.request

sys.dont_write_bytecode = True

SPEC = importlib.util.spec_from_file_location('publication', Path(__file__).parents[1] / 'ci/library-publication.py')
PUB = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(PUB)


class PublicationTest(unittest.TestCase):
    component_name = 'concurrency'
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.bundle = Path(self.temp.name)
        self.component = PUB.COMPONENTS[self.component_name]
        self.artifact = self.component['artifact']
        self.version = '0.3.0-rc.1'
        self.folder = self.bundle / 'repository' / PUB.relative_path(self.version, self.component)
        self.folder.mkdir(parents=True)
        for suffix in PUB.SUFFIXES:
            (self.folder / (self.artifact + '-' + self.version + suffix)).write_bytes(b'fixture ' + suffix.encode())
        PUB.write_identity(self.bundle, dict(group=PUB.GROUP, artifact=self.artifact,
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

    def test_component_selection_rejects_another_library_bundle(self):
        other = PUB.COMPONENTS['diagnostics' if self.component_name == 'concurrency' else 'concurrency']
        self.assertEqual(PUB.read_bundle(self.bundle, expected_component=self.component)['artifact'], self.artifact)
        with self.assertRaisesRegex(ValueError, 'component'):
            PUB.read_bundle(self.bundle, expected_component=other)
        for command in ('check', 'sign', 'central', 'github', 'consume'):
            with self.subTest(command=command), patch.object(sys, 'argv', [
                    'library-publication.py', command, '--bundle', str(self.bundle), '--component',
                    'diagnostics' if self.component_name == 'concurrency' else 'concurrency']), \
                    patch.object(PUB, 'request') as request, patch.object(PUB, 'run') as run:
                with self.assertRaisesRegex(ValueError, 'component'):
                    PUB.main()
                request.assert_not_called()
                run.assert_not_called()

    def test_release_tag_is_bound_to_component_and_release_branch(self):
        tag = self.artifact + '-v' + self.version
        self.assertEqual(PUB.release_version(self.component_name, tag, 'release-0.3.0'), self.version)
        invalid = [tag.replace(self.artifact, 'ioc-unreviewed'), self.artifact + '-v0.3.0-SNAPSHOT']
        for value in invalid:
            with self.subTest(tag=value), self.assertRaisesRegex(ValueError, 'component tag'):
                PUB.release_version(self.component_name, value, 'release-0.3.0')
        for branch in ('main', 'r030-libraries', 'release-0.4.0'):
            with self.subTest(branch=branch), self.assertRaises(ValueError):
                PUB.release_version(self.component_name, tag, branch)
        other = 'diagnostics' if self.component_name == 'concurrency' else 'concurrency'
        with self.assertRaisesRegex(ValueError, 'component tag'):
            PUB.release_version(other, tag, 'release-0.3.0')

    def test_unknown_artifact_is_not_admitted(self):
        metadata = PUB.read_bundle(self.bundle)
        metadata['artifact'] = 'ioc-unreviewed'
        (self.bundle / 'identity.json').write_text(json.dumps(metadata))
        with self.assertRaisesRegex(ValueError, 'coordinates'):
            PUB.read_bundle(self.bundle)

    def build_outputs(self):
        """Supply minimal archive bytes to probe packaging rejections offline."""
        root = Path(self.temp.name) / 'checkout'
        target = root / self.component['module'] / 'target'
        target.mkdir(parents=True)
        pom = ('<project xmlns="http://maven.apache.org/POM/4.0.0">'
               '<groupId>' + PUB.GROUP + '</groupId><artifactId>' + self.artifact + '</artifactId>'
               '<version>' + self.version + '</version><name>Fixture</name><description>Fixture</description>'
               '<url>https://example.invalid</url><licenses/><developers/><scm/></project>')
        (target / 'publication.pom').write_text(pom)
        for suffix, extension in (('.jar', '.class'), ('-sources.jar', '.java'), ('-javadoc.jar', '.html')):
            with zipfile.ZipFile(target / (self.artifact + '-' + self.version + suffix), 'w') as archive:
                for marker in self.component['markers']:
                    archive.writestr(self.component['package'] + marker + extension,
                                     b'\xca\xfe\xba\xbe\x00\x00\x00\x41' if extension == '.class' else b'fixture')
                archive.writestr('META-INF/LICENSE', 'MIT fixture')
                if suffix == '.jar':
                    archive.writestr('META-INF/maven/' + PUB.GROUP + '/' + self.artifact + '/pom.xml', pom)
        return root, target

    def prepare_fixture(self, root, name):
        def git(args, **kwargs):
            return subprocess.CompletedProcess(args, 0, stdout='a' * 40 if 'rev-parse' in args else '')
        output = Path(self.temp.name) / name
        with patch.object(PUB, 'ROOT', root), patch.object(PUB, 'run', side_effect=git):
            PUB.prepare(output, self.version, self.component)
        return output

    def test_packaging_rejects_coupled_pom_before_creating_bundle(self):
        root, target = self.build_outputs()
        self.prepare_fixture(root, 'valid')
        original = (target / 'publication.pom').read_text()
        for field in ('parent', 'dependencies', 'dependencyManagement', 'repositories', 'build', 'profiles'):
            with self.subTest(field=field):
                (target / 'publication.pom').write_text(original.replace('</project>', '<' + field + '/></project>'))
                with self.assertRaisesRegex(ValueError, 'POM coupling'):
                    self.prepare_fixture(root, field)
                self.assertFalse((Path(self.temp.name) / field).exists())

    def test_packaging_rejects_wrong_pom_and_incomplete_or_foreign_archives(self):
        root, target = self.build_outputs()
        pom_path = target / 'publication.pom'
        original = pom_path.read_bytes()
        pom_path.write_bytes(original.replace(self.artifact.encode(), b'ioc-unreviewed'))
        with self.assertRaisesRegex(ValueError, 'identity mismatch'):
            self.prepare_fixture(root, 'wrong-pom')
        pom_path.write_bytes(original)
        for suffix in PUB.SUFFIXES[1:]:
            with self.subTest(suffix=suffix):
                path = target / (self.artifact + '-' + self.version + suffix)
                original_jar = path.read_bytes()
                with zipfile.ZipFile(path, 'w') as archive:
                    archive.writestr('placeholder', 'empty public API')
                with self.assertRaisesRegex(ValueError, 'archive contents'):
                    self.prepare_fixture(root, 'missing-api')
                path.write_bytes(original_jar)
        jar = target / (self.artifact + '-' + self.version + '.jar')
        with zipfile.ZipFile(jar, 'a') as archive:
            archive.writestr('other/service/Foreign.class', b'\xca\xfe\xba\xbe\x00\x00\x00\x41')
        with self.assertRaisesRegex(ValueError, 'foreign library classes'):
            self.prepare_fixture(root, 'foreign-class')

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

    def test_bundle_rejects_symbolic_manifest(self):
        manifest = self.bundle / 'identity.json'
        target = self.bundle / 'outside-identity.json'
        manifest.rename(target)
        manifest.symlink_to(target)
        with self.assertRaisesRegex(ValueError, 'symbolic manifest'):
            PUB.read_bundle(self.bundle)

    def test_bundle_rejects_symbolic_recovery_files_before_side_effects(self):
        for name in ('central-deployment-id.txt', 'central-bundle.zip'):
            with self.subTest(name=name):
                path = self.bundle / name
                target = self.bundle / 'outside-recovery'
                target.write_bytes(b'preserve this file')
                path.symlink_to(target)
                try:
                    with patch.object(PUB, 'request') as request, patch.object(PUB, 'run') as run:
                        with self.assertRaisesRegex(ValueError, 'symbolic recovery file'):
                            PUB.publish_central(self.bundle, True, '')
                        request.assert_not_called()
                        run.assert_not_called()
                    self.assertEqual(target.read_bytes(), b'preserve this file')
                finally:
                    path.unlink()

    def test_bundle_rejects_symbolic_repository_directories_before_side_effects(self):
        repository = self.bundle / 'repository'
        for index, path in enumerate((repository, repository / 'io', self.folder)):
            with self.subTest(path=path.relative_to(self.bundle)):
                target = self.bundle / ('outside-directory-' + str(index))
                path.rename(target)
                path.symlink_to(target, target_is_directory=True)
                try:
                    for command in ('check', 'sign', 'central', 'github', 'consume'):
                        with patch.object(sys, 'argv', [
                                'library-publication.py', command, '--bundle', str(self.bundle),
                                '--component', self.component_name]), \
                                patch.object(PUB, 'request') as request, patch.object(PUB, 'run') as run:
                            with self.assertRaisesRegex(ValueError, 'symbolic'):
                                PUB.main()
                            request.assert_not_called()
                            run.assert_not_called()
                finally:
                    path.unlink()
                    target.rename(path)

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

    def test_repository_read_redirect_drops_cross_origin_authorization(self):
        original = urllib.request.Request(
            PUB.GITHUB + '/artifact.pom', headers={'Authorization': 'Basic secret'})
        redirected = PUB.SafeReadRedirect().redirect_request(
            original, None, 302, 'Found', {},
            'https://mavenregistryv2prod.blob.core.windows.net/container/artifact.pom?signature=value')
        self.assertEqual(redirected.get_method(), 'GET')
        self.assertNotIn('Authorization', dict(redirected.header_items()))

    def test_repository_redirect_rejects_write_and_https_downgrade(self):
        write = urllib.request.Request(
            PUB.GITHUB + '/artifact.pom', data=b'artifact', method='PUT')
        with self.assertRaises(urllib.error.HTTPError):
            PUB.SafeReadRedirect().redirect_request(
                write, None, 302, 'Found', {}, 'https://uploads.github.com/artifact.pom')
        read = urllib.request.Request(PUB.GITHUB + '/artifact.pom')
        with self.assertRaises(urllib.error.HTTPError):
            PUB.SafeReadRedirect().redirect_request(
                read, None, 302, 'Found', {}, 'http://maven.pkg.github.com/artifact.pom')

    def test_partial_central_visibility_does_not_create_deployment(self):
        self.signed()
        with patch.object(PUB, 'compare_remote', return_value=['one']), patch.object(PUB, 'request') as request:
            with self.assertRaisesRegex(ValueError, 'partial'):
                PUB.publish_central(self.bundle, False, '')
            request.assert_not_called()

    def test_published_central_recovery_retains_supplied_deployment_id(self):
        self.signed()
        deployment_id = '12345678-1234-1234-1234-123456789abc'
        with patch.object(PUB, 'compare_remote', return_value=[]):
            PUB.publish_central(self.bundle, True, deployment_id)
        self.assertEqual(
            (self.bundle / 'central-deployment-id.txt').read_text(), deployment_id + '\n')

    def test_central_resume_validates_without_reupload_or_release(self):
        metadata = self.signed()
        deployment_id = '12345678-1234-1234-1234-123456789abc'
        (self.bundle / 'central-deployment-id.txt').write_text(deployment_id)
        name = self.artifact + '-' + self.version + '-' + PUB.digest((self.bundle / 'identity.json').read_bytes())
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


class DiagnosticsPublicationTest(PublicationTest):
    component_name = 'diagnostics'


class ProxyTest(unittest.TestCase):
    def test_proxy_adds_transport_only_without_repositories_or_mirrors(self):
        with patch.dict(os.environ, {'HTTPS_PROXY': 'http://user:p%40ss@proxy.invalid:8080'}, clear=True):
            settings = PUB.ET.Element('settings')
            PUB.configure_proxy(settings)
        self.assertEqual(settings.findtext('proxies/proxy/host'), 'proxy.invalid')
        self.assertEqual(settings.findtext('proxies/proxy/password'), 'p@ss')
        self.assertIsNone(settings.find('repositories'))
        self.assertIsNone(settings.find('mirrors'))

    def test_missing_proxy_leaves_settings_empty(self):
        with patch.dict(os.environ, {}, clear=True):
            settings = PUB.ET.Element('settings')
            PUB.configure_proxy(settings)
        self.assertEqual(len(settings), 0)

    def test_invalid_proxy_is_rejected_without_echoing_credentials(self):
        with patch.dict(os.environ, {'HTTPS_PROXY': 'socks5://user:secret@proxy.invalid'}, clear=True):
            with self.assertRaisesRegex(ValueError, '^invalid HTTPS proxy configuration$'):
                PUB.configure_proxy(PUB.ET.Element('settings'))


if __name__ == '__main__':
    unittest.main()
