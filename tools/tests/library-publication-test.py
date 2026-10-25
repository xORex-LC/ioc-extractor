#!/usr/bin/env python3
"""Offline contract tests for immutable publication and recovery decisions."""
import importlib.util
import json
import os
import runpy
import subprocess
from pathlib import Path
import sys
import tempfile
import textwrap
import unittest
import zipfile
from unittest.mock import patch
import urllib.error
import urllib.request

sys.dont_write_bytecode = True

SPEC = importlib.util.spec_from_file_location('publication', Path(__file__).parents[1] / 'ci/library-publication.py')
PUB = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(PUB)


def consumer_pom(component, version):
    dependencies = ''.join('<dependency><groupId>' + group + '</groupId><artifactId>' + artifact
                           + '</artifactId><version>' + dependency_version + '</version>'
                           '<scope>compile</scope></dependency>'
                           for group, artifact, dependency_version in PUB.dependencies_for(component, version))
    return ('<project xmlns="http://maven.apache.org/POM/4.0.0">'
            '<modelVersion>4.0.0</modelVersion>'
            '<groupId>' + PUB.GROUP + '</groupId><artifactId>' + component['artifact'] + '</artifactId>'
            '<version>' + version + '</version><name>Fixture</name><description>Fixture</description>'
            '<url>https://example.invalid</url><licenses/><developers/><scm/>'
            + ('<dependencies>' + dependencies + '</dependencies>' if dependencies else '') + '</project>')


def fixture_gpg(env, *args, data=None):
    """Retain a disposable-key failure's stderr without exposing stdin or exported key bytes."""
    result = subprocess.run(['gpg', '--batch', '--pinentry-mode', 'loopback', '--passphrase-fd', '0', *args],
                            input=data or b'test-passphrase', capture_output=True, timeout=30, env=env)
    if result.returncode:
        raise AssertionError('temporary GPG fixture failed (' + str(result.returncode) + ') during '
                             + args[0] + ': ' + result.stderr.decode(errors='replace'))
    return result.stdout


class GpgFixtureTest(unittest.TestCase):
    def test_failure_retains_stderr_but_never_input_or_exported_private_key(self):
        failure = subprocess.CompletedProcess([], 2, stdout=b'private-key-material', stderr=b'agent unavailable')
        with patch.object(subprocess, 'run', return_value=failure):
            with self.assertRaisesRegex(AssertionError, 'agent unavailable') as raised:
                fixture_gpg({}, '--export-secret-keys', data=b'private-passphrase')
        self.assertNotIn('private-key-material', str(raised.exception))
        self.assertNotIn('private-passphrase', str(raised.exception))


class BundleContract:
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
        (self.folder / (self.artifact + '-' + self.version + '.pom')).write_text(
            consumer_pom(self.component, self.version))
        PUB.write_identity(self.bundle, dict(group=PUB.GROUP, artifact=self.artifact,
                                           version=self.version, commit='a' * 40, dirty=False, signed=False))

    def signed(self, bundle=None):
        bundle = bundle or self.bundle
        metadata = PUB.read_bundle(bundle)
        for name in list(metadata['files']):
            path = bundle / 'repository' / name
            Path(str(path) + '.asc').write_bytes(b'fixture signature')
            for algorithm in ('md5', 'sha1', 'sha256'):
                Path(str(path) + '.' + algorithm).write_text(PUB.hashlib.new(algorithm, path.read_bytes()).hexdigest())
        metadata.update(signed=True, fingerprint=PUB.FINGERPRINT)
        PUB.write_identity(bundle, metadata)
        return PUB.read_bundle(bundle, signed=True)

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
        pom = consumer_pom(self.component, self.version)
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
        fields = ['parent', 'dependencyManagement', 'repositories', 'pluginRepositories',
                  'distributionManagement', 'build', 'profiles']
        if not self.component['dependencies']:
            fields.append('dependencies')
        for field in fields:
            with self.subTest(field=field):
                (target / 'publication.pom').write_text(original.replace('</project>', '<' + field + '/></project>'))
                with self.assertRaisesRegex(ValueError, 'POM coupling'):
                    self.prepare_fixture(root, field)
                self.assertFalse((Path(self.temp.name) / field).exists())

    def test_pom_rejects_non_jar_packaging_and_duplicate_identity_fields(self):
        original = consumer_pom(self.component, self.version).encode()
        for extra in (b'<packaging>pom</packaging>', b'<groupId>other</groupId>',
                      b'<version>0.0.0</version>', b'<artifactId>other</artifactId>',
                      b'<properties><localSetting>private</localSetting></properties>', b'<modules/>',
                      b'<packaging xmlns="">pom</packaging>', b'<dependencies xmlns=""/>'):
            with self.subTest(extra=extra), self.assertRaises(ValueError):
                PUB.validate_consumer_pom(original.replace(b'</project>', extra + b'</project>'), self.version, self.component)

    def test_manifest_hashes_cannot_admit_an_unreviewed_dependency(self):
        pom = self.folder / (self.artifact + '-' + self.version + '.pom')
        provider = ('<dependency><groupId>ch.qos.logback</groupId><artifactId>logback-classic</artifactId>'
                    '<version>1.5.38</version></dependency>')
        content = pom.read_text()
        content = content.replace('</dependencies>', provider + '</dependencies>') if self.component['dependencies'] \
            else content.replace('</project>', '<dependencies>' + provider + '</dependencies></project>')
        pom.write_text(content)
        PUB.write_identity(self.bundle, dict(group=PUB.GROUP, artifact=self.artifact,
                                           version=self.version, commit='a' * 40, dirty=False, signed=False))
        for command in ('check', 'sign', 'central', 'github', 'consume'):
            with self.subTest(command=command), patch.object(sys, 'argv', [
                    'library-publication.py', command, '--bundle', str(self.bundle),
                    '--component', self.component_name]), patch.object(PUB, 'request') as request, \
                    patch.object(PUB, 'run') as run:
                with self.assertRaisesRegex(ValueError, 'dependencies'):
                    PUB.main()
                request.assert_not_called()
                run.assert_not_called()

    def test_consumer_checks_bytes_and_repository_origin_for_all_four_artifacts(self):
        metadata = PUB.read_bundle(self.bundle)
        cache = Path(self.temp.name) / 'cache'
        resolved = cache / PUB.relative_path(self.version, self.component)
        PUB.shutil.copytree(self.folder, resolved)
        originals = [self.artifact + '-' + self.version + suffix for suffix in PUB.SUFFIXES]
        origins = resolved / '_remote.repositories'
        origins.write_text('\n'.join(name + '>library-target=' for name in originals))
        PUB.verify_resolved_artifacts(metadata, self.component, cache)
        for name in originals:
            with self.subTest(artifact=name):
                origins.write_text('\n'.join(item + ('>public-dependencies=' if item == name else '>library-target=')
                                             for item in originals))
                with self.assertRaisesRegex(ValueError, 'selected repository'):
                    PUB.verify_resolved_artifacts(metadata, self.component, cache)
        origins.write_text('\n'.join(name + '>library-target=' for name in originals))
        with origins.open('a') as output:
            output.write('\n' + originals[0] + '>public-dependencies=\n')
        with self.assertRaisesRegex(ValueError, 'selected repository'):
            PUB.verify_resolved_artifacts(metadata, self.component, cache)
        origins.write_text('\n'.join(name + '>library-target=' for name in originals))
        (resolved / originals[1]).write_bytes(b'rebuilt library')
        with self.assertRaisesRegex(ValueError, 'different artifact bytes'):
            PUB.verify_resolved_artifacts(metadata, self.component, cache)

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

    def test_snapshot_is_not_release(self):
        with self.assertRaisesRegex(ValueError, 'snapshot'):
            PUB.version_check('0.3.0-SNAPSHOT', release=True)

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


class PublicationTest(BundleContract, unittest.TestCase):
    def test_real_gpg_signing_with_disposable_test_key(self):
        with tempfile.TemporaryDirectory(prefix='ioc-test-key-') as home:
            env = {**os.environ, 'GNUPGHOME': home}
            def gpg(*args, data=None):
                return fixture_gpg(env, *args, data=data)
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

    def test_dirty_checkout_cannot_be_signed(self):
        metadata = PUB.read_bundle(self.bundle)
        metadata['dirty'] = True
        PUB.write_identity(self.bundle, metadata)
        with self.assertRaisesRegex(ValueError, 'clean source'):
            PUB.sign(self.bundle)

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


class LoggingPublicationTest(PublicationTest):
    component_name = 'logging'

    def test_dependency_contract_rejects_missing_optional_wrong_scope_and_extra_fields(self):
        original = consumer_pom(self.component, self.version).encode()
        group, artifact, version = (value.encode() for value in PUB.dependencies_for(self.component, self.version)[0])
        invalid = [
            original.replace(b'<scope>compile</scope>', b'<scope>provided</scope>'),
            original.replace(b'<scope>compile</scope>', b'<scope>runtime</scope>'),
            original.replace(b'<scope>compile</scope>', b'<scope>test</scope>'),
            original.replace(b'<scope>compile</scope>', b'<optional>true</optional>'),
            original.replace(b'<scope>compile</scope>', b'<scope xmlns="">provided</scope>'),
            original.replace(b'<scope>compile</scope>', b'<optional xmlns="">true</optional>'),
            original.replace(b'<scope>compile</scope>', b'<type>pom</type>'),
            original.replace(b'<scope>compile</scope>', b'<classifier>sources</classifier>'),
            original.replace(b'<scope>compile</scope>', b'<exclusions/>'),
            original.replace(version, b'${slf4j.version}'),
            original.replace(version, b'[2.0,3.0)'),
            original.replace(version, b'2.0.17'),
            original.replace(b'<scope>compile</scope>', b'<scope>compile</scope><scope>test</scope>'),
            original.replace(b'<artifactId>' + artifact + b'</artifactId>', b'<artifactId>slf4j-simple</artifactId>'),
            original.replace(b'<groupId>' + group + b'</groupId>', b'<groupId>com.iocextractor</groupId>'),
            original.replace(b'<dependencies>', b'<ignored>').replace(b'</dependencies>', b'</ignored>'),
            original.replace(b'<dependency>', b'<ignored>').replace(b'</dependency>', b'</ignored>'),
        ]
        for index, content in enumerate(invalid):
            with self.subTest(case=index), self.assertRaises(ValueError):
                PUB.validate_consumer_pom(content, self.version, self.component)
        empty = original[:original.index(b'<dependencies>')] + b'</project>'
        with self.assertRaisesRegex(ValueError, 'dependencies'):
            PUB.validate_consumer_pom(empty, self.version, self.component)
        dependency = original[original.index(b'<dependency>'):original.index(b'</dependency>') + len(b'</dependency>')]
        with self.assertRaisesRegex(ValueError, 'dependencies'):
            PUB.validate_consumer_pom(original.replace(b'</dependencies>', dependency + b'</dependencies>'),
                                      self.version, self.component)
        for content in [original.replace(b'<scope>compile</scope>', b''),
                        original.replace(b'<scope>compile</scope>', b'<type>jar</type><optional>false</optional>')]:
            PUB.validate_consumer_pom(content, self.version, self.component)


class ConfigurationPublicationTest(LoggingPublicationTest):
    component_name = 'configuration'

    def test_each_required_dependency_is_checked_independently(self):
        original = consumer_pom(self.component, self.version).encode()
        for group, artifact, version in PUB.dependencies_for(self.component, self.version):
            with self.subTest(artifact=artifact):
                identity = ('<artifactId>' + artifact + '</artifactId><version>' + version + '</version>').encode()
                with self.assertRaisesRegex(ValueError, 'dependencies'):
                    PUB.validate_consumer_pom(original.replace(identity, identity.replace(version.encode(), b'0.0.0')),
                                              self.version, self.component)


class EventsPublicationTest(PublicationTest):
    component_name = 'events'


class EventsPackagingTest(BundleContract, unittest.TestCase):
    component_name = 'events'

    def test_pending_release_admission_prevents_signing_and_remote_writes(self):
        for signed in (False, True):
            if signed:
                self.signed()
            for command in ('sign', 'central', 'github'):
                with self.subTest(signed=signed, command=command), patch.object(sys, 'argv', [
                        'library-publication.py', command, '--bundle', str(self.bundle),
                        '--component', self.component_name]), patch.object(PUB, 'request') as request, \
                        patch.object(PUB, 'run') as run, patch.object(PUB, 'authorization') as authorization, \
                        patch.dict(self.component, release_admitted=False):
                    with self.assertRaisesRegex(ValueError, 'E5 release admission|signed bundle'):
                        PUB.main()
                    request.assert_not_called()
                    run.assert_not_called()
                    authorization.assert_not_called()

    def test_packaging_and_recovery_accept_another_lockstep_version(self):
        self.version = '0.4.0-rc.2'
        root, _ = self.build_outputs()
        bundle = self.prepare_fixture(root, 'another-version')
        metadata = PUB.read_bundle(bundle, expected_component=self.component)
        self.assertEqual(metadata['version'], self.version)
        self.assertEqual(len(metadata['files']), 4)


class EventsSpringPackagingTest(EventsPackagingTest):
    component_name = 'events-spring'
    test_dependency_contract_rejects_missing_optional_wrong_scope_and_extra_fields = (
        LoggingPublicationTest.test_dependency_contract_rejects_missing_optional_wrong_scope_and_extra_fields)
    test_each_required_dependency_is_checked_independently = (
        ConfigurationPublicationTest.test_each_required_dependency_is_checked_independently)

    def test_contract_dependency_must_match_the_bundle_version(self):
        original = consumer_pom(self.component, self.version).encode()
        dependency = b'<artifactId>ioc-platform-events</artifactId><version>' + self.version.encode() + b'</version>'
        for version in (b'0.3.0-SNAPSHOT', b'0.3.0-rc.2', b'[0.3,0.4)', b'${revision}'):
            with self.subTest(version=version), self.assertRaises(ValueError):
                PUB.validate_consumer_pom(original.replace(dependency, dependency.replace(self.version.encode(), version)),
                                          self.version, self.component)

    def core_bundle(self, version=None):
        core = PUB.COMPONENTS['events']
        version = version or self.version
        bundle = Path(self.temp.name) / ('core-' + version)
        path = bundle / 'repository' / PUB.relative_path(version, core)
        path.mkdir(parents=True)
        for suffix in PUB.SUFFIXES:
            (path / (core['artifact'] + '-' + version + suffix)).write_bytes(b'core fixture ' + suffix.encode())
        (path / (core['artifact'] + '-' + version + '.pom')).write_text(consumer_pom(core, version))
        PUB.write_identity(bundle, dict(group=PUB.GROUP, artifact=core['artifact'],
                                       version=version, commit='b' * 40, dirty=False, signed=False))
        return bundle

    def qualified_core(self):
        core = self.core_bundle()
        self.signed(core)
        evidence = core / 'qualification'
        evidence.mkdir()
        for repository in ('central', 'github'):
            (evidence / (repository + '.log')).write_text(repository + ': cold consumer passed\n')
        with patch.dict(os.environ, GITHUB_RUN_ID='1234', GITHUB_REF_NAME='release-0.3.0'):
            PUB.record_qualification(core, evidence)
        return core

    def test_real_adapter_signing_uses_an_already_qualified_core(self):
        original = PUB.sign
        def sign_with_core(bundle):
            original(bundle, self.qualified_core())
        with patch.object(PUB, 'sign', side_effect=sign_with_core):
            PublicationTest.test_real_gpg_signing_with_disposable_test_key(self)

    def test_adapter_release_requires_qualified_core_before_credentials(self):
        unsigned = self.core_bundle()
        for command in ('sign', 'central', 'github'):
            if command == 'central':
                self.signed()
            for dependency in (None, unsigned):
                with self.subTest(command=command, dependency=dependency), \
                        patch.object(PUB, 'request') as request, patch.object(PUB, 'run') as run, \
                        patch.object(PUB, 'authorization') as authorization:
                    with self.assertRaisesRegex(ValueError, 'dependency-bundle|signed bundle'):
                        if command == 'sign':
                            PUB.sign(self.bundle, dependency)
                        elif command == 'central':
                            PUB.publish_central(self.bundle, True, '', dependency)
                        else:
                            PUB.publish_github(self.bundle, dependency)
                    request.assert_not_called()
                    run.assert_not_called()
                    authorization.assert_not_called()
        self.signed(unsigned)
        with self.assertRaisesRegex(ValueError, 'qualification directory'):
            PUB.release_dependencies(PUB.read_bundle(self.bundle), unsigned)

    def test_core_receipt_rejects_other_bytes_destinations_identity_or_logs(self):
        core = self.qualified_core()
        path = core / 'qualification/qualification.json'
        original = json.loads(path.read_text())
        self.assertEqual(PUB.core_qualification(core, self.version, 'b' * 40, '1234'), original)
        for field, wrong in [('schema', 2), ('identity_sha256', 'c' * 64), ('version', '0.4.0-rc.1'),
                              ('commit', 'c' * 40), ('repositories', {'central': PUB.CENTRAL}),
                              ('dependency_sha256', 'c' * 64), ('run_id', '../run'),
                              ('logs', {'central': 'c' * 64, 'github': 'c' * 64}), ('branch', 'main')]:
            with self.subTest(field=field):
                path.write_text(json.dumps({**original, field: wrong}))
                with self.assertRaises(ValueError):
                    PUB.core_qualification(core, self.version)
        path.write_text(json.dumps(original))
        for version, commit, run in [('0.4.0-rc.1', 'b' * 40, '1234'),
                                     (self.version, 'a' * 40, '1234'), (self.version, 'b' * 40, '4321')]:
            with self.assertRaisesRegex(ValueError, 'differs'):
                PUB.core_qualification(core, version, commit, run)
        (core / 'qualification/central.log').write_text('different log')
        with self.assertRaisesRegex(ValueError, 'log differs'):
            PUB.core_qualification(core, self.version)

    def test_admission_checks_successful_core_run_and_both_repositories(self):
        core = self.qualified_core()
        with patch.object(PUB, 'completed_publication_run') as run, \
                patch.object(PUB, 'authorization', return_value={}), \
                patch.object(PUB, 'compare_remote', return_value=[]) as compare:
            PUB.check_core_release(core, self.version, 'b' * 40, '1234')
        run.assert_called_once_with('1234', 'release-0.3.0', successful=True)
        self.assertEqual([call.args[:2] for call in compare.call_args_list],
                         [(core, PUB.CENTRAL), (core, PUB.GITHUB)])

    def test_wrong_core_commit_stops_admission_before_network_or_credentials(self):
        core = self.qualified_core()
        with patch.object(PUB, 'request') as request, patch.object(PUB, 'authorization') as authorization:
            with self.assertRaisesRegex(ValueError, 'commit differs'):
                PUB.check_core_release(core, self.version, 'a' * 40, '1234')
            request.assert_not_called()
            authorization.assert_not_called()

    def test_core_qualification_rejects_symbolic_evidence(self):
        core = self.qualified_core()
        evidence = core / 'qualification'
        evidence.rename(core / 'linked-evidence')
        evidence.symlink_to(core / 'linked-evidence', target_is_directory=True)
        with self.assertRaisesRegex(ValueError, 'qualification directory'):
            PUB.core_qualification(core, self.version)

    def test_incomplete_consumer_logs_cannot_emit_a_success_receipt(self):
        core = self.core_bundle()
        self.signed(core)
        evidence = core / 'qualification'
        evidence.mkdir()
        (evidence / 'central.log').write_text('first consumer passed')
        with patch.dict(os.environ, GITHUB_RUN_ID='1234', GITHUB_REF_NAME='release-0.3.0'):
            with self.assertRaisesRegex(ValueError, 'missing consumer log'):
                PUB.record_qualification(core, evidence)
        self.assertFalse((evidence / 'qualification.json').exists())

    def test_missing_published_core_aborts_adapter_writes(self):
        core = self.qualified_core()
        self.signed()
        for repository in ('central', 'github'):
            with self.subTest(repository=repository), patch.object(PUB, 'authorization', return_value={}), \
                    patch.object(PUB, 'compare_remote', return_value=['missing-core.jar']) as compare, \
                    patch.object(PUB, 'request') as request:
                with self.assertRaisesRegex(ValueError, 'core release is incomplete in ' + repository):
                    if repository == 'central':
                        PUB.publish_central(self.bundle, True, '', core)
                    else:
                        PUB.publish_github(self.bundle, core)
                self.assertEqual(compare.call_args.args[0], core)
                request.assert_not_called()

    def test_adapter_recovery_preserves_original_core_and_adapter_bundles(self):
        core = self.qualified_core()
        metadata = self.signed()
        before = {str(path): path.read_bytes() for bundle in (self.bundle, core)
                  for path in bundle.rglob('*') if path.is_file()}
        missing = next(name for name in metadata['files'] if name.endswith('-sources.jar'))
        with patch.object(PUB, 'authorization', return_value={}), patch.object(PUB, 'request') as request, \
                patch.object(PUB, 'compare_remote', side_effect=[[], [missing], []]):
            PUB.publish_github(self.bundle, core)
        request.assert_called_once()
        self.assertEqual(request.call_args.args[:3],
                         (PUB.GITHUB + '/' + missing, 'PUT', (self.bundle / 'repository' / missing).read_bytes()))
        with patch.object(PUB, 'compare_remote', side_effect=[[], []]), patch.object(PUB, 'request') as request:
            PUB.publish_central(self.bundle, True, '', core)
            request.assert_not_called()
        self.assertEqual(before, {str(path): path.read_bytes() for bundle in (self.bundle, core)
                                  for path in bundle.rglob('*') if path.is_file()})

    def test_adapter_requires_a_valid_same_version_core_bundle_before_remote_access(self):
        wrong_version = self.core_bundle('0.4.0-rc.1')
        for dependency, message in [(None, 'requires --dependency-bundle'),
                                     (self.bundle, 'component'), (wrong_version, 'version differs')]:
            for repository in ('local', 'central', 'github'):
                with self.subTest(dependency=dependency, repository=repository), \
                        patch.object(PUB, 'request') as request, patch.object(PUB, 'run') as run, \
                        patch.object(PUB, 'authorization') as authorization:
                    with self.assertRaisesRegex(ValueError, message):
                        PUB.consume(self.bundle, repository, dependency)
                    request.assert_not_called()
                    run.assert_not_called()
                    authorization.assert_not_called()

    def test_local_adapter_consumer_sees_both_exact_bundles_in_one_selected_repository(self):
        core = self.core_bundle()
        before = {str(p): p.read_bytes() for bundle in (self.bundle, core)
                  for p in (bundle / 'repository').rglob('*') if p.is_file()}
        def inspect(metadata, component, fixture, repository, url, artifacts):
            self.assertEqual(repository, 'local')
            self.assertEqual(len(artifacts), 2)
            target = Path(PUB.urllib.parse.unquote(PUB.urllib.parse.urlparse(url).path))
            files = [p for p in target.rglob('*') if p.is_file()]
            self.assertEqual(len(files), 8)
            for own, descriptor in artifacts:
                for path, expected in own['files'].items():
                    self.assertEqual(PUB.digest((target / path).read_bytes()), expected)
            self.assertNotEqual(target, core / 'repository')
            self.assertNotEqual(target, self.bundle / 'repository')
        with patch.object(PUB, 'consume_fixture', side_effect=inspect) as consume:
            PUB.consume(self.bundle, 'local', core)
            consume.assert_called_once()
        self.assertEqual(before, {str(p): p.read_bytes() for bundle in (self.bundle, core)
                                  for p in (bundle / 'repository').rglob('*') if p.is_file()})

    def test_remote_adapter_checks_core_bytes_at_the_same_selected_endpoint(self):
        core = self.core_bundle()
        with patch.object(PUB, 'compare_remote', side_effect=[[], ['missing-core.jar']]) as compare, \
                patch.object(PUB, 'consume_fixture') as consume:
            with self.assertRaisesRegex(ValueError, 'remote bundle incomplete'):
                PUB.consume(self.bundle, 'central', core)
            self.assertEqual([call.args[:2] for call in compare.call_args_list],
                             [(self.bundle, PUB.CENTRAL), (core, PUB.CENTRAL)])
            consume.assert_not_called()


class EventsGraphTest(unittest.TestCase):
    def graph(self, project, own, public):
        nodes = [dict(groupId=PUB.GROUP, artifactId=a, version=v, type='jar') for a, v in own.items()]
        nodes += [dict(groupId=g, artifactId=a, version=v, type='jar') for (g, a), v in public.items()]
        path = project / 'target/dependencies.json'
        path.parent.mkdir(exist_ok=True)
        path.write_text(json.dumps(dict(groupId='example.consumer', children=nodes)))

    def test_jdk_graph_rejects_any_extra_dependency_or_wrong_own_version(self):
        with tempfile.TemporaryDirectory() as folder:
            project = Path(folder)
            metadata = {'version': '0.3.0-rc.1'}
            own = {'ioc-platform-events': metadata['version']}
            self.graph(project, own, {})
            PUB.events_graph(metadata, PUB.COMPONENTS['events'], project)
            for public in [{('org.slf4j', 'slf4j-api'): '2.0.18'},
                           {('com.iocextractor', 'ioc-application'): metadata['version']}]:
                self.graph(project, own, public)
                with self.assertRaisesRegex(ValueError, 'public dependency closure'):
                    PUB.events_graph(metadata, PUB.COMPONENTS['events'], project)
            self.graph(project, {'ioc-platform-events': '0.3.0-rc.2'}, {})
            with self.assertRaisesRegex(ValueError, 'own dependency closure/version'):
                PUB.events_graph(metadata, PUB.COMPONENTS['events'], project)

    def test_spring_graph_checks_all_public_versions_and_own_core(self):
        with tempfile.TemporaryDirectory() as folder:
            project = Path(folder)
            metadata = {'version': '0.3.0-rc.1'}
            own = {'ioc-events-spring': metadata['version'], 'ioc-platform-events': metadata['version']}
            public = {**{('org.springframework', 'spring-' + n): '7.0.9'
                         for n in ('core', 'context', 'aop', 'beans', 'expression')},
                      **{('io.micrometer', 'micrometer-' + n): '1.16.7' for n in ('observation', 'commons')},
                      ('commons-logging', 'commons-logging'): '1.3.5',
                      ('org.jspecify', 'jspecify'): '1.0.0', ('org.slf4j', 'slf4j-api'): '2.0.18'}
            self.graph(project, own, public)
            PUB.events_graph(metadata, PUB.COMPONENTS['events-spring'], project)
            for key in public:
                with self.subTest(dependency=key):
                    self.graph(project, own, {**public, key: '0.0.0'})
                    with self.assertRaisesRegex(ValueError, 'public dependency closure'):
                        PUB.events_graph(metadata, PUB.COMPONENTS['events-spring'], project)
            for additional in [('org.springframework.boot', 'spring-boot'), ('ch.qos.logback', 'logback-classic')]:
                self.graph(project, own, {**public, additional: '1'})
                with self.assertRaisesRegex(ValueError, 'public dependency closure'):
                    PUB.events_graph(metadata, PUB.COMPONENTS['events-spring'], project)
            self.graph(project, {'ioc-events-spring': metadata['version']}, public)
            with self.assertRaisesRegex(ValueError, 'own dependency closure/version'):
                PUB.events_graph(metadata, PUB.COMPONENTS['events-spring'], project)


class ConsumerGuardTest(unittest.TestCase):
    def test_workflow_exposes_only_release_admitted_components(self):
        workflow = (PUB.ROOT / '.github/workflows/library-publication.yml').read_text()
        choices = PUB.re.search(r'options: \[([^\]]+)\]', workflow).group(1).split(', ')
        self.assertEqual(choices, [name for name, component in PUB.COMPONENTS.items()
                                  if component['fixtures'] and component.get('release_admitted', False)])

    def test_completed_run_provenance_allows_failed_recovery_but_not_failed_core(self):
        record = dict(path='.github/workflows/library-publication.yml', event='workflow_dispatch',
                      status='completed', head_branch='release-0.3.0', conclusion='success')
        with patch.dict(os.environ, GITHUB_REPOSITORY='owner/repo', GITHUB_TOKEN='private-token'):
            for field, value in [('path', 'other.yml'), ('event', 'push'), ('status', 'in_progress'),
                                  ('head_branch', 'main'), ('conclusion', 'failure')]:
                with self.subTest(field=field), patch.object(PUB, 'request', return_value=
                        json.dumps({**record, field: value}).encode()):
                    with self.assertRaises(ValueError):
                        PUB.completed_publication_run('1234', 'release-0.3.0', successful=True)
            with patch.object(PUB, 'request', return_value=json.dumps({**record, 'conclusion': 'failure'}).encode()):
                PUB.completed_publication_run('1234', 'release-0.3.0')
            with patch.object(PUB, 'request') as request:
                with self.assertRaises(ValueError):
                    PUB.completed_publication_run('../1234', 'release-0.3.0')
                request.assert_not_called()

    def test_workflow_admission_enforces_core_input_and_retained_recovery(self):
        workflow = (PUB.ROOT / '.github/workflows/library-publication.yml').read_text()
        code = textwrap.dedent(workflow.split("python3 - <<'PY'\n", 1)[1].split('\n          PY', 1)[0])
        namespace = {'release_version': PUB.release_version, 'completed_publication_run': PUB.completed_publication_run}
        with tempfile.TemporaryDirectory() as folder:
            def git(command, **kwargs):
                return 'tag' if command[1] == 'cat-file' else 'b' * 40
            for component, resume, core, valid in [('events', '', '', True), ('events', '', '1234', False),
                    ('events-spring', '', '', False), ('events-spring', '', '1234', True),
                    ('events-spring', '5678', '', True), ('events-spring', '5678', '1234', False)]:
                with self.subTest(component=component, resume=resume, core=core), \
                        patch.dict(os.environ, RELEASE_TAG=PUB.COMPONENTS[component]['artifact'] + '-v0.3.0-rc.1',
                                   RELEASE_BRANCH='release-0.3.0', RESUME_RUN_ID=resume, CORE_RUN_ID=core,
                                   DEPLOYMENT_ID='12345678-1234-1234-1234-123456789abc' if resume else '',
                                   LIBRARY_COMPONENT=component, GITHUB_OUTPUT=str(Path(folder) / 'outputs')), \
                        patch.object(runpy, 'run_path', return_value=namespace), \
                        patch.object(subprocess, 'check_output', side_effect=git), \
                        patch.object(subprocess, 'run'), patch.object(PUB, 'completed_publication_run') as check:
                    namespace['completed_publication_run'] = check
                    if valid:
                        exec(compile(code, 'workflow-admission', 'exec'), {})
                        if resume:
                            check.assert_called_once_with(resume, 'release-0.3.0')
                        elif core:
                            check.assert_called_once_with(core, 'release-0.3.0', successful=True)
                        else:
                            check.assert_not_called()
                    else:
                        if component == 'events-spring' and not resume and not core:
                            check.side_effect = ValueError('invalid publication run ID')
                        with self.assertRaises((ValueError, AssertionError)):
                            exec(compile(code, 'workflow-admission', 'exec'), {})

    def test_empty_fixture_list_still_cannot_pass_consumer_admission(self):
        with self.assertRaisesRegex(ValueError, 'E4 independent consumer'):
            PUB.require_consumer_fixtures({'artifact': 'unqualified', 'fixtures': ()})

    def test_unrelated_component_cannot_accept_an_own_dependency_bundle(self):
        with self.assertRaisesRegex(ValueError, 'unexpected dependency bundle'):
            PUB.consumption_dependencies({}, PUB.COMPONENTS['configuration'], Path('/unused'))

    def test_routing_negative_rejects_unrelated_resolution_failure_and_success(self):
        for code, output in [(0, 'Could not resolve dependencies: Could not find artifact '
                             + PUB.GROUP + ':ioc-platform-events:jar:0.3.0-SNAPSHOT'),
                             (1, 'ioc-platform-events: Could not resolve dependencies: '
                              'Could not find artifact org.springframework:spring-core:jar:7.0.9')]:
            with self.subTest(code=code), tempfile.TemporaryDirectory() as folder:
                project = Path(folder) / 'consumer'
                project.mkdir()
                (project / 'pom.xml').write_text('<project><repositories></repositories></project>')
                with patch.object(PUB.subprocess, 'run', return_value=
                        subprocess.CompletedProcess([], code, stdout=output, stderr='')):
                    with self.assertRaisesRegex(ValueError, 'did not fail closed'):
                        PUB.qualify_repository_routing({'version': '0.3.0-SNAPSHOT', 'artifact': 'ioc-platform-events'},
                                                       PUB.COMPONENTS['events'], project, 'file:///alternate')

    def test_events_examples_are_extracted_exactly_for_their_supported_classpath(self):
        blocks = PUB.re.findall(r'```java\n(.*?)\n```',
                               (PUB.ROOT / 'docs/guides/events-library.md').read_text(), PUB.re.DOTALL)
        for component, spring in [('events', False), ('events-spring', True)]:
            with self.subTest(component=component), tempfile.TemporaryDirectory() as folder:
                project = Path(folder)
                PUB.populate_examples(PUB.COMPONENTS[component], component + '-consumer', project)
                source = project / 'src/main/java'
                self.assertEqual((source / 'LocalEventsExample.java').read_text(), blocks[0])
                self.assertEqual((source / 'SpringEventsExample.java').exists(), spring)
                if spring:
                    self.assertEqual((source / 'SpringEventsExample.java').read_text(), blocks[1])

    def test_central_download_group_admits_only_own_and_reviewed_public_groups(self):
        for repository, url in [('central', PUB.CENTRAL), ('github', PUB.GITHUB), ('local', 'file:///selected')]:
            with self.subTest(repository=repository), tempfile.TemporaryDirectory() as folder:
                work = Path(folder)
                PUB.consumer_settings(work, repository, url)
                for identifier in ('central', 'library-target'):
                    groups = (work / 'repository-filters' / ('groupId-' + identifier + '.txt')).read_text().splitlines()
                    self.assertIn(PUB.GROUP, groups)
                    self.assertEqual('org.springframework.boot' in groups, repository == 'central')
                    self.assertNotIn('com.iocextractor', groups)
                    self.assertNotIn('unreviewed.group', groups)
                for identifier in ('public-dependencies', 'public-plugins', 'public-fallback'):
                    groups = (work / 'repository-filters' / ('groupId-' + identifier + '.txt')).read_text().splitlines()
                    self.assertIn('org.springframework.boot', groups)
                    self.assertNotIn(PUB.GROUP, groups)

    def test_workflow_propagates_each_consumer_failure_through_tee(self):
        workflow = (PUB.ROOT / '.github/workflows/library-publication.yml').read_text()
        step = workflow.split('      - name: Qualify each repository with a separate cold consumer\n', 1)[1]
        commands = step.split('        run: |\n', 1)[1].split('      - name:', 1)[0]
        commands = '\n'.join(line[10:] for line in commands.splitlines())
        for central, github, receipt in [(17, 0, 0), (0, 23, 0), (0, 0, 39), (0, 0, 0)]:
            with self.subTest(central=central, github=github, receipt=receipt), tempfile.TemporaryDirectory() as folder:
                work = Path(folder)
                stub = work / 'bin/python3'
                stub.parent.mkdir()
                stub.write_text('#!/bin/sh\n'
                                'printf "%s\\n" "$*" >> "$CONSUMER_CALLS"\n'
                                'case "$*" in\n'
                                '  *"--repository central"*) exit "$CENTRAL_EXIT" ;;\n'
                                '  *"--repository github"*) exit "$GITHUB_EXIT" ;;\n'
                                '  *"record-qualification"*) exit "$RECEIPT_EXIT" ;;\n'
                                '  *) exit 99 ;;\n'
                                'esac\n')
                stub.chmod(0o755)
                calls = work / 'calls.txt'
                env = {**os.environ, 'PATH': str(stub.parent) + os.pathsep + os.environ['PATH'],
                       'LIBRARY_COMPONENT': 'configuration', 'CONSUMER_CALLS': str(calls),
                       'CENTRAL_EXIT': str(central), 'GITHUB_EXIT': str(github), 'RECEIPT_EXIT': str(receipt)}
                result = subprocess.run(['bash', '-e', '-c', commands], cwd=work, env=env,
                                        capture_output=True, text=True, timeout=10)
                self.assertEqual(result.returncode, central or github or receipt)
                self.assertTrue((work / 'library-consumer-evidence/central.log').exists())
                self.assertEqual('--repository github' in calls.read_text(), central == 0)
                self.assertEqual('record-qualification' in calls.read_text(), central == github == 0)

    def test_public_allowlist_rejects_own_groups_even_with_whitespace(self):
        for group in (PUB.GROUP, PUB.GROUP + '.future', 'com.iocextractor'):
            with self.subTest(group=group), tempfile.TemporaryDirectory() as folder:
                root = Path(folder)
                source = root / 'tools/ci/library-publication-groups.txt'
                source.parent.mkdir(parents=True)
                source.write_text('org.springframework\n  ' + group + '  \n')
                with patch.object(PUB, 'ROOT', root), self.assertRaisesRegex(ValueError, 'own groups'):
                    PUB.consumer_settings(root, 'local', 'file:///selected')

    def test_github_credentials_are_scoped_to_the_selected_target(self):
        with tempfile.TemporaryDirectory() as folder, patch.dict(os.environ, {}, clear=True):
            work = Path(folder)
            PUB.consumer_settings(work, 'github', PUB.GITHUB)
            ns = {'m': 'http://maven.apache.org/SETTINGS/1.2.0'}
            settings = PUB.ET.parse(work / 'settings.xml')
            servers = settings.findall('m:servers/m:server', ns)
            self.assertEqual(len(servers), 1)
            self.assertEqual(servers[0].findtext('m:id', namespaces=ns), 'library-target')
            self.assertEqual(servers[0].findtext('m:password', namespaces=ns), '${env.GITHUB_TOKEN}')

    def test_routing_has_explicit_rules_for_mirrored_and_original_repository_ids(self):
        with tempfile.TemporaryDirectory() as folder, patch.dict(os.environ, {}, clear=True):
            work = Path(folder)
            command = PUB.consumer_settings(work, 'local', 'file:///selected')
            self.assertIn('-Daether.remoteRepositoryFilter.groupId=true', command)
            ns = {'m': 'http://maven.apache.org/SETTINGS/1.2.0'}
            settings = PUB.ET.parse(work / 'settings.xml')
            mirrors = {m.findtext('m:id', namespaces=ns): m.findtext('m:mirrorOf', namespaces=ns)
                       for m in settings.findall('m:mirrors/m:mirror', ns)}
            self.assertEqual(mirrors['library-target'], 'central')
            self.assertEqual(mirrors['public-fallback'], '*,!central,!public-dependencies,!public-plugins')
            for identifier in (*mirrors, 'central'):
                groups = (work / 'repository-filters' / ('groupId-' + identifier + '.txt')).read_text().splitlines()
                self.assertEqual(PUB.GROUP in groups, identifier in ('library-target', 'central'))
                self.assertNotIn('com.iocextractor', groups)

    def test_configuration_fixture_uses_the_exact_guide_and_driver(self):
        with tempfile.TemporaryDirectory() as folder:
            project = Path(folder)
            PUB.populate_examples(PUB.COMPONENTS['configuration'], 'configuration-consumer', project)
            source = project / 'src/main/java/examples/configuration'
            self.assertEqual((source / 'ConfigurationRecipeMain.java').read_bytes(),
                             (PUB.ROOT / 'bootstrap/ioc-app/src/test/resources/consumer/configuration/ConfigurationRecipeMain.java').read_bytes())
            guide = (PUB.ROOT / 'docs/guides/configuration-library-check.md').read_text()
            for language, path in [('java', source / 'CollectorConfigurationCheck.java'), ('yaml', project / 'collector.yml')]:
                self.assertEqual(path.read_text(), PUB.re.findall(r'```' + language + r'\n(.*?)\n```', guide, PUB.re.DOTALL)[0])

    def test_negative_checks_reject_success_and_an_unrelated_failure(self):
        with tempfile.TemporaryDirectory() as folder:
            project = Path(folder)
            for code, output in [(0, 'COMPILATION ERROR org.slf4j'),
                                 (1, 'org.slf4j download failed: HTTP 503')]:
                (project / 'target').mkdir()
                with self.subTest(code=code), patch.object(PUB.subprocess, 'run', return_value=
                        subprocess.CompletedProcess([], code, stdout=output, stderr='')) as run:
                    with self.assertRaisesRegex(ValueError, 'expected reason'):
                        PUB.negative_dependency_checks(['mvn'], project, {})
                    self.assertIn('-o', run.call_args.args[0])


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
