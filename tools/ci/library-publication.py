#!/usr/bin/env python3
"""Prepare, sign and distribute one immutable, independently consumable library bundle."""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[2]
GROUP = 'io.github.xorex-lc'
ARTIFACT = 'ioc-platform-concurrency'
FINGERPRINT = 'F69BA7E0F7494982E6E1B483DF54073D8BBFA9F9'
CENTRAL = 'https://repo.maven.apache.org/maven2'
GITHUB = 'https://maven.pkg.github.com/xorex-lc/ioc-extractor'
PORTAL = 'https://central.sonatype.com/api/v1/publisher'
SUFFIXES = ('.pom', '.jar', '-sources.jar', '-javadoc.jar')


def require(condition, message):
    if not condition:
        raise ValueError(message)


def run(args, **kwargs):
    return subprocess.run(args, check=True, timeout=900, **kwargs)


def digest(data):
    return hashlib.sha256(data).hexdigest()


def version_check(version, release=False):
    require(re.fullmatch(r'\d+\.\d+\.\d+(?:-rc\.\d+|-SNAPSHOT)?', version), 'invalid version')
    require(not release or not version.endswith('-SNAPSHOT'), 'snapshot publication is not admitted')
    return version


def relative_path(version):
    return Path(*GROUP.split('.')) / ARTIFACT / version


def read_bundle(bundle, signed=False):
    metadata = json.loads((bundle / 'identity.json').read_text())
    version_check(metadata['version'])
    require(metadata['group'] == GROUP and metadata['artifact'] == ARTIFACT, 'unexpected coordinates')
    require(re.fullmatch('[0-9a-f]{40}', metadata['commit']), 'invalid source identity')
    require(bool(metadata['files']), 'empty manifest')
    prefix = relative_path(metadata['version'])
    for name, expected in metadata['files'].items():
        path = Path(name)
        require(path.parent == prefix and path.name.startswith(ARTIFACT + '-'), 'invalid artifact path')
        artifact = bundle / 'repository' / path
        require(artifact.is_file() and not artifact.is_symlink(), 'missing or symbolic artifact')
        require(digest(artifact.read_bytes()) == expected, 'artifact checksum mismatch: ' + name)
    stem = str(prefix / (ARTIFACT + '-' + metadata['version']))
    originals = {stem + suffix for suffix in SUFFIXES}
    expected_names = set(originals)
    if metadata.get('signed'):
        require(metadata.get('fingerprint') == FINGERPRINT, 'unexpected signer')
        expected_names |= {name + '.asc' for name in originals}
        expected_names |= {name + suffix for name in originals for suffix in ('.md5', '.sha1', '.sha256')}
    require(set(metadata['files']) == expected_names, 'unexpected or incomplete artifact manifest')
    actual_names = {str(p.relative_to(bundle / 'repository')) for p in (bundle / 'repository').rglob('*') if p.is_file()}
    require(actual_names == expected_names, 'unmanifested repository files')
    require(not signed or metadata.get('signed'), 'signed bundle required')
    return metadata


def write_identity(bundle, metadata):
    metadata['files'] = {str(p.relative_to(bundle / 'repository')): digest(p.read_bytes())
                         for p in sorted((bundle / 'repository').rglob('*')) if p.is_file()}
    (bundle / 'identity.json').write_text(json.dumps(metadata, indent=2, sort_keys=True) + '\n')


def prepare(bundle, version):
    version_check(version)
    require(not bundle.exists(), 'output already exists; preserve or choose a new bundle path')
    target = ROOT / 'platform/platform-concurrency/target'
    pom = target / 'publication.pom'
    xml = ET.parse(pom).getroot()
    ns = {'m': 'http://maven.apache.org/POM/4.0.0'}
    require('${' not in pom.read_text(), 'unresolved consumer POM property')
    for field, value in [('groupId', GROUP), ('artifactId', ARTIFACT), ('version', version)]:
        require(xml.findtext('m:' + field, namespaces=ns) == value, 'consumer POM identity mismatch')
    for field in ('parent', 'dependencies', 'dependencyManagement', 'repositories', 'build', 'profiles'):
        require(xml.find('m:' + field, ns) is None, 'unexpected consumer POM coupling: ' + field)
    for field in ('name', 'description', 'url', 'licenses', 'developers', 'scm'):
        require(xml.find('m:' + field, ns) is not None, 'missing POM metadata: ' + field)
    stem = ARTIFACT + '-' + version
    sources = {'.pom': pom, **{suffix: target / (stem + suffix) for suffix in SUFFIXES[1:]}}
    for suffix, source in sources.items():
        require(source.is_file(), 'missing build artifact: ' + str(source))
        if suffix != '.pom':
            with zipfile.ZipFile(source) as archive:
                require(archive.testzip() is None, 'corrupt archive')
                names = archive.namelist()
                marker = 'BoundedKeyedSerialExecutor' + ('.class' if suffix == '.jar' else '.java' if suffix == '-sources.jar' else '.html')
                require(any(n.endswith(marker) for n in names), 'missing real archive contents: ' + suffix)
                if suffix == '.jar':
                    require(not any(n.startswith('BOOT-INF/') for n in names), 'bootable JAR is not a library')
                    embedded_pom = 'META-INF/maven/' + GROUP + '/' + ARTIFACT + '/pom.xml'
                    require(archive.read(embedded_pom) == pom.read_bytes(), 'embedded consumer POM differs')
                    require('META-INF/LICENSE' in names, 'missing distribution license')
                    classes = [archive.read(n) for n in names if n.endswith('.class')]
                    require(classes and all(int.from_bytes(c[6:8], 'big') == 65 for c in classes),
                            'library bytecode must target Java 21')
    destination = bundle / 'repository' / relative_path(version)
    destination.mkdir(parents=True)
    for suffix, source in sources.items():
        shutil.copyfile(source, destination / (stem + suffix))
    commit = run(['git', '-C', str(ROOT), 'rev-parse', 'HEAD'], capture_output=True, text=True).stdout.strip()
    dirty = bool(run(['git', '-C', str(ROOT), 'status', '--porcelain'], capture_output=True, text=True).stdout)
    write_identity(bundle, {'group': GROUP, 'artifact': ARTIFACT, 'version': version, 'commit': commit,
                            'dirty': dirty, 'signed': False})
    read_bundle(bundle)


def sign(bundle):
    metadata = read_bundle(bundle)
    require(not metadata.get('signed'), 'bundle is already signed; reuse it unchanged')
    require(metadata.get('dirty') is False, 'release signing requires a clean source checkout')
    version_check(metadata['version'], release=True)
    key = os.environ['GPG_PRIVATE_KEY']
    passphrase = os.environ['GPG_PASSPHRASE']
    with tempfile.TemporaryDirectory(prefix='ioc-signing-') as home:
        env = {k: v for k, v in os.environ.items() if not k.startswith(('GPG_', 'MAVEN_CENTRAL_'))}
        env['GNUPGHOME'] = home
        try:
            run(['gpg', '--batch', '--import'], input=key.encode(), capture_output=True, env=env)
            listing = run(['gpg', '--batch', '--with-colons', '--list-secret-keys', '--fingerprint'],
                          capture_output=True, text=True, env=env).stdout
            fingerprints = [line.split(':')[9] for line in listing.splitlines() if line.startswith('fpr:')]
            require(fingerprints and fingerprints[0] == FINGERPRINT, 'private key does not match configured fingerprint')
            for name in list(metadata['files']):
                path = bundle / 'repository' / name
                run(['gpg', '--batch', '--yes', '--pinentry-mode', 'loopback', '--passphrase-fd', '0',
                     '--local-user', FINGERPRINT, '--armor', '--detach-sign', str(path)],
                    input=passphrase.encode(), capture_output=True, env=env)
                run(['gpg', '--batch', '--verify', str(path) + '.asc', str(path)], capture_output=True, env=env)
                for algorithm in ('md5', 'sha1', 'sha256'):
                    path.with_name(path.name + '.' + algorithm).write_text(hashlib.new(algorithm, path.read_bytes()).hexdigest())
        finally:
            subprocess.run(['gpgconf', '--kill', 'gpg-agent'], capture_output=True, env=env, timeout=10)
    metadata.update(signed=True, fingerprint=FINGERPRINT)
    write_identity(bundle, metadata)
    read_bundle(bundle, signed=True)


class SafeReadRedirect(urllib.request.HTTPRedirectHandler):
    """Follow repository read redirects without forwarding credentials."""

    @staticmethod
    def origin(url):
        parsed = urllib.parse.urlparse(url)
        port = parsed.port or (443 if parsed.scheme.lower() == 'https' else 80)
        return parsed.scheme.lower(), (parsed.hostname or '').lower(), port

    def redirect_request(self, req, fp, code, msg, headers, newurl):
        target = urllib.parse.urlparse(newurl)
        if req.get_method() not in ('GET', 'HEAD') or target.scheme.lower() != 'https' \
                or target.username is not None or target.password is not None:
            raise urllib.error.HTTPError(req.full_url, code, msg, headers, fp)
        redirected = super().redirect_request(req, fp, code, msg, headers, newurl)
        if self.origin(req.full_url) != self.origin(newurl):
            redirected.remove_header('Authorization')
        return redirected


def request(url, method='GET', data=None, headers=None, missing_ok=False):
    # Repository downloads may redirect to external blob storage. Follow only
    # read redirects over HTTPS and never forward credentials across origins.
    opener = urllib.request.build_opener(SafeReadRedirect())
    try:
        with opener.open(urllib.request.Request(url, data=data, headers=headers or {}, method=method), timeout=60) as response:
            return response.read()
    except urllib.error.HTTPError as failure:
        if missing_ok and failure.code == 404:
            return None
        raise ValueError('repository request failed with HTTP ' + str(failure.code)) from None


def authorization(kind):
    if kind == 'central':
        value = os.environ['MAVEN_CENTRAL_USERNAME'] + ':' + os.environ['MAVEN_CENTRAL_PASSWORD']
        scheme = 'Bearer '
    else:
        value = os.environ['GITHUB_ACTOR'] + ':' + os.environ['GITHUB_TOKEN']
        scheme = 'Basic '
    return {'Authorization': scheme + base64.b64encode(value.encode()).decode()}


def compare_remote(bundle, base, headers=None):
    metadata = read_bundle(bundle, signed=True)
    missing = []
    for name, expected in metadata['files'].items():
        content = request(base + '/' + name, headers=headers, missing_ok=True)
        if content is None:
            missing.append(name)
        else:
            if name.endswith(('.md5', '.sha1', '.sha256')):
                require(content.strip() == (bundle / 'repository' / name).read_bytes().strip(),
                        'published checksum differs: ' + name)
            else:
                require(digest(content) == expected, 'published version differs: ' + name)
    return missing


def publish_github(bundle):
    headers = authorization('github')
    missing = compare_remote(bundle, GITHUB, headers)
    # Maven repositories use HTTP PUT for exact artifact paths. Existing bytes
    # are checked first; a retry sends only missing files, never replaces a release.
    for name in missing:
        request(GITHUB + '/' + name, 'PUT', (bundle / 'repository' / name).read_bytes(),
                {**headers, 'Content-Type': 'application/octet-stream'})
    require(not compare_remote(bundle, GITHUB, headers), 'GitHub publication is incomplete')


def publish_central(bundle, publish, deployment_id):
    metadata = read_bundle(bundle, signed=True)
    version_check(metadata['version'], release=True)
    missing = compare_remote(bundle, CENTRAL)
    if not missing:
        print('Central already contains the exact bundle')
        return
    require(len(missing) == len(metadata['files']), 'Central visibility is partial; retry after propagation')
    headers = authorization('central')
    if not deployment_id and (bundle / 'central-deployment-id.txt').exists():
        deployment_id = (bundle / 'central-deployment-id.txt').read_text().strip()
    if not deployment_id:
        archive = bundle / 'central-bundle.zip'
        with zipfile.ZipFile(archive, 'w', zipfile.ZIP_DEFLATED) as output:
            for name in sorted(metadata['files']):
                output.write(bundle / 'repository' / name, name)
        boundary = uuid.uuid4().hex
        body = ('--' + boundary + '\r\nContent-Disposition: form-data; name="bundle"; filename="central-bundle.zip"\r\n'
                'Content-Type: application/octet-stream\r\n\r\n').encode() + archive.read_bytes() + ('\r\n--' + boundary + '--\r\n').encode()
        name = ARTIFACT + '-' + metadata['version'] + '-' + digest((bundle / 'identity.json').read_bytes())
        url = PORTAL + '/upload?' + urllib.parse.urlencode({'name': name, 'publishingType': 'USER_MANAGED'})
        deployment_id = request(url, 'POST', body, {**headers, 'Content-Type': 'multipart/form-data; boundary=' + boundary}).decode().strip()
        require(re.fullmatch('[a-fA-F0-9-]{36}', deployment_id), 'invalid deployment identifier')
        (bundle / 'central-deployment-id.txt').write_text(deployment_id + '\n')
    require(re.fullmatch('[a-fA-F0-9-]{36}', deployment_id), 'invalid deployment identifier')
    expected_name = ARTIFACT + '-' + metadata['version'] + '-' + digest((bundle / 'identity.json').read_bytes())
    (bundle / 'central-deployment-id.txt').write_text(deployment_id + '\n')
    deadline = time.monotonic() + 900
    released = False
    while time.monotonic() < deadline:
        status = json.loads(request(PORTAL + '/status?id=' + deployment_id, 'POST', headers=headers))
        require(status.get('deploymentName') == expected_name, 'deployment does not belong to this bundle')
        state = status.get('deploymentState')
        require(state in ('PENDING', 'VALIDATING', 'VALIDATED', 'PUBLISHING', 'PUBLISHED'), 'Central rejected the deployment; inspect portal')
        if state == 'VALIDATED':
            if not publish:
                print('Central validated deployment ' + deployment_id + '; publication not requested')
                return
            if not released:
                request(PORTAL + '/deployment/' + deployment_id, 'POST', headers=headers)
                released = True
        if state == 'PUBLISHED' and not compare_remote(bundle, CENTRAL):
            return
        time.sleep(5)
    raise ValueError('Central did not finish within 15 minutes; retain deployment ID and bundle for recovery')


def consume(bundle, repository):
    metadata = read_bundle(bundle)
    require(repository in ('local', 'central', 'github'), 'unknown consumer repository')
    url = {'local': (bundle / 'repository').resolve().as_uri(), 'central': CENTRAL, 'github': GITHUB}[repository]
    if repository != 'local':
        require(not compare_remote(bundle, url, authorization('github') if repository == 'github' else None), 'remote bundle incomplete')
    with tempfile.TemporaryDirectory(prefix='ioc-library-consumer-') as folder:
        work = Path(folder)
        shutil.copytree(ROOT / 'tools/fixtures/library-consumer', work / 'consumer')
        settings = ET.Element('settings', xmlns='http://maven.apache.org/SETTINGS/1.2.0')
        mirror = ET.SubElement(ET.SubElement(settings, 'mirrors'), 'mirror')
        for name, value in [('id', 'library-target'), ('mirrorOf', 'central'), ('url', url)]:
            ET.SubElement(mirror, name).text = value
        if repository == 'github':
            server = ET.SubElement(ET.SubElement(settings, 'servers'), 'server')
            for name, value in [('id', 'library-target'), ('username', '${env.GITHUB_ACTOR}'), ('password', '${env.GITHUB_TOKEN}')]:
                ET.SubElement(server, name).text = value
        settings_path = work / 'settings.xml'
        ET.ElementTree(settings).write(settings_path, encoding='UTF-8', xml_declaration=True)
        # Fresh caches and empty user/global settings prevent reactor-local or
        # developer configuration from masking a missing published dependency.
        empty = work / 'global-settings.xml'
        empty.write_text('<settings/>')
        env = {k: v for k, v in os.environ.items() if not k.startswith(('GPG_', 'MAVEN_CENTRAL_'))}
        for name in ('MAVEN_ARGS', 'MAVEN_OPTS', 'JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS'):
            env.pop(name, None)
        run([str(ROOT / 'mvnw'), '-B', '-ntp', '-s', str(settings_path), '-gs', str(empty),
             '-Dmaven.repo.local=' + str(work / 'cache'), '-Dlibrary.version=' + metadata['version'],
             'compile', 'org.codehaus.mojo:exec-maven-plugin:3.5.0:java'], cwd=work / 'consumer', env=env)
        resolved = work / 'cache' / relative_path(metadata['version'])
        for suffix in SUFFIXES:
            name = ARTIFACT + '-' + metadata['version'] + suffix
            require(digest((resolved / name).read_bytes()) == metadata['files'][str(relative_path(metadata['version']) / name)], 'consumer resolved different artifact bytes')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=('prepare', 'check', 'sign', 'central', 'github', 'consume'))
    parser.add_argument('--bundle', type=Path, required=True)
    parser.add_argument('--version')
    parser.add_argument('--commit')
    parser.add_argument('--repository', default='local')
    parser.add_argument('--publish', action='store_true')
    parser.add_argument('--deployment-id', default='')
    args = parser.parse_args()
    bundle = args.bundle.resolve()
    if args.command == 'prepare':
        prepare(bundle, args.version or '')
    elif args.command == 'check':
        metadata = read_bundle(bundle)
        require(not args.version or metadata['version'] == args.version, 'unexpected bundle version')
        require(not args.commit or metadata['commit'] == args.commit, 'unexpected bundle commit')
    elif args.command == 'sign':
        sign(bundle)
    elif args.command == 'central':
        publish_central(bundle, args.publish, args.deployment_id)
    elif args.command == 'github':
        version_check(read_bundle(bundle, signed=True)['version'], release=True)
        publish_github(bundle)
    else:
        consume(bundle, args.repository)


if __name__ == '__main__':
    try:
        main()
    except (ValueError, KeyError, OSError, subprocess.SubprocessError, ET.ParseError, zipfile.BadZipFile) as failure:
        # Never echo subprocess output, credentials or server response bodies.
        print('library-publication: ' + (str(failure) if isinstance(failure, ValueError) else type(failure).__name__), file=sys.stderr)
        sys.exit(1)
