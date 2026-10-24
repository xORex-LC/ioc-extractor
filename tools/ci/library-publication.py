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
# Each component has an exact dependency contract; adding a component does not
# relax the zero-dependency checks for the existing JDK-only libraries.
COMPONENTS = {
    'concurrency': {
        'artifact': ARTIFACT,
        'module': 'platform/platform-concurrency',
        'package': 'com/iocextractor/platform/concurrent/',
        'markers': ('BoundedKeyedSerialExecutor',),
        'fixtures': ('library-consumer',),
        'dependencies': (),
    },
    'diagnostics': {
        'artifact': 'ioc-platform-diagnostics-api',
        'module': 'platform/platform-diagnostics-api',
        'package': 'com/iocextractor/platform/diagnostics/',
        'markers': ('Diagnostic', 'result/DiagnosticBatch', 'sink/ResilientDiagnosticSink'),
        'fixtures': ('diagnostics-consumer',),
        'dependencies': (),
    },
    'logging': {
        'artifact': 'ioc-platform-logging-slf4j',
        'module': 'platform/platform-logging-slf4j',
        'package': 'com/iocextractor/platform/logging/',
        'markers': ('LogEvents', 'MdcScope', 'ecs/EcsFields', 'redaction/LogRedaction'),
        'fixtures': ('logging-consumer', 'logging-boot-consumer'),
        'dependencies': (('org.slf4j', 'slf4j-api', '2.0.18'),),
    },
    'configuration': {
        'artifact': 'ioc-config-spring-boot',
        'module': 'adapters/adapter-config-spring-boot',
        'package': 'com/iocextractor/config/boot/',
        'markers': ('PropertySchema', 'PropertyInspector', 'PropertyInspection',
                    'PropertyMigration', 'MigrationInspector', 'MigrationInspection'),
        'fixtures': ('configuration-consumer',),
        'dependencies': (('org.springframework.boot', 'spring-boot', '4.0.8'),
                         ('org.springframework', 'spring-core', '7.0.9')),
    },
}


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


def release_version(component_name, tag, branch):
    """Admit only this component's tag on its matching product release branch."""
    require(component_name in COMPONENTS, 'unknown component')
    artifact = COMPONENTS[component_name]['artifact']
    tag_match = re.fullmatch(re.escape(artifact) + r'-v(\d+\.\d+\.\d+(?:-rc\.\d+)?)', tag)
    branch_match = re.fullmatch(r'release-(\d+\.\d+\.\d+)', branch)
    require(tag_match is not None, 'invalid component tag')
    require(branch_match is not None, 'select a release-X.Y.Z branch')
    version = tag_match.group(1)
    require(re.sub(r'-rc\.\d+$', '', version) == branch_match.group(1),
            'component version does not match selected release branch')
    return version


def component_for(metadata):
    require(metadata['group'] == GROUP, 'unexpected coordinates')
    for component in COMPONENTS.values():
        if component['artifact'] == metadata['artifact']:
            return component
    raise ValueError('unexpected coordinates')


def relative_path(version, component=None):
    component = component or COMPONENTS['concurrency']
    return Path(*GROUP.split('.')) / component['artifact'] / version


def read_bundle(bundle, signed=False, expected_component=None):
    manifest = bundle / 'identity.json'
    require(manifest.is_file() and not manifest.is_symlink(), 'missing or symbolic manifest')
    repository = bundle / 'repository'
    require(repository.is_dir() and not repository.is_symlink(), 'missing or symbolic repository')
    for name in ('central-deployment-id.txt', 'central-bundle.zip'):
        require(not (bundle / name).is_symlink(), 'symbolic recovery file')
    metadata = json.loads(manifest.read_text())
    version_check(metadata['version'])
    component = component_for(metadata)
    require(expected_component is None or component == expected_component, 'unexpected bundle component')
    artifact_id = component['artifact']
    require(re.fullmatch('[0-9a-f]{40}', metadata['commit']), 'invalid source identity')
    require(bool(metadata['files']), 'empty manifest')
    prefix = relative_path(metadata['version'], component)
    for name, expected in metadata['files'].items():
        path = Path(name)
        require(path.parent == prefix and path.name.startswith(artifact_id + '-'), 'invalid artifact path')
        artifact = repository / path
        # Check directory links before reading bytes: the file itself can be
        # regular while a parent redirects signing or recovery outside the bundle.
        require(not any(parent.is_symlink() for parent in artifact.parents
                        if parent.is_relative_to(repository)), 'symbolic artifact directory')
        require(artifact.is_file() and not artifact.is_symlink(), 'missing or symbolic artifact')
        require(digest(artifact.read_bytes()) == expected, 'artifact checksum mismatch: ' + name)
    stem = str(prefix / (artifact_id + '-' + metadata['version']))
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
    pom = repository / (stem + '.pom')
    validate_consumer_pom(pom.read_bytes(), metadata['version'], component)
    return metadata


def write_identity(bundle, metadata):
    metadata['files'] = {str(p.relative_to(bundle / 'repository')): digest(p.read_bytes())
                         for p in sorted((bundle / 'repository').rglob('*')) if p.is_file()}
    (bundle / 'identity.json').write_text(json.dumps(metadata, indent=2, sort_keys=True) + '\n')


def validate_consumer_pom(content, version, component):
    """Check the same dependency contract before packaging and during recovery."""
    xml = ET.fromstring(content)
    ns = {'m': 'http://maven.apache.org/POM/4.0.0'}
    require(xml.tag == '{' + ns['m'] + '}project', 'invalid consumer POM root')
    require(all(element.tag.startswith('{' + ns['m'] + '}') for element in xml.iter()),
            'invalid consumer POM namespace')
    fields = [child.tag.removeprefix('{' + ns['m'] + '}') for child in xml]
    require(len(fields) == len(set(fields)), 'duplicate consumer POM fields')
    require(b'${' not in content, 'unresolved consumer POM property')
    require(xml.findtext('m:modelVersion', namespaces=ns) == '4.0.0', 'invalid consumer POM model version')
    for field, value in [('groupId', GROUP), ('artifactId', component['artifact']), ('version', version)]:
        require(xml.findtext('m:' + field, namespaces=ns) == value, 'consumer POM identity mismatch')
    require(xml.findtext('m:packaging', 'jar', ns) == 'jar', 'consumer POM must package a JAR')
    for field in ('parent', 'dependencyManagement', 'repositories', 'pluginRepositories',
                  'distributionManagement', 'build', 'profiles'):
        require(xml.find('m:' + field, ns) is None, 'unexpected consumer POM coupling: ' + field)
    require(set(fields) <= {'modelVersion', 'groupId', 'artifactId', 'version', 'packaging',
                            'name', 'description', 'url', 'licenses', 'developers', 'scm', 'dependencies'},
            'unexpected consumer POM fields')
    for field in ('name', 'description', 'url', 'licenses', 'developers', 'scm'):
        require(xml.find('m:' + field, ns) is not None, 'missing POM metadata: ' + field)
    dependencies = xml.findall('m:dependencies', ns)
    if not component['dependencies']:
        require(not dependencies, 'unexpected consumer POM coupling: dependencies')
        return
    require(len(dependencies) == 1, 'missing or duplicate consumer POM dependencies')
    actual = []
    for dependency in dependencies[0]:
        require(dependency.tag == '{' + ns['m'] + '}dependency', 'unexpected dependency element')
        fields = [child.tag.removeprefix('{' + ns['m'] + '}') for child in dependency]
        require(len(fields) == len(set(fields)) and set(fields) <=
                {'groupId', 'artifactId', 'version', 'scope', 'type', 'optional'},
                'unexpected dependency fields')
        require(dependency.findtext('m:scope', 'compile', ns) == 'compile'
                and dependency.findtext('m:type', 'jar', ns) == 'jar'
                and dependency.findtext('m:optional', 'false', ns) == 'false',
                'dependency must be a required compile JAR')
        actual.append(tuple(dependency.findtext('m:' + field, namespaces=ns)
                            for field in ('groupId', 'artifactId', 'version')))
    require(tuple(actual) == component['dependencies'], 'unexpected consumer POM dependencies')


def prepare(bundle, version, component=None):
    component = component or COMPONENTS['concurrency']
    artifact_id = component['artifact']
    version_check(version)
    require(not bundle.exists(), 'output already exists; preserve or choose a new bundle path')
    target = ROOT / component['module'] / 'target'
    pom = target / 'publication.pom'
    validate_consumer_pom(pom.read_bytes(), version, component)
    stem = artifact_id + '-' + version
    sources = {'.pom': pom, **{suffix: target / (stem + suffix) for suffix in SUFFIXES[1:]}}
    for suffix, source in sources.items():
        require(source.is_file(), 'missing build artifact: ' + str(source))
        if suffix != '.pom':
            with zipfile.ZipFile(source) as archive:
                require(archive.testzip() is None, 'corrupt archive')
                names = archive.namelist()
                extension = '.class' if suffix == '.jar' else '.java' if suffix == '-sources.jar' else '.html'
                require(all(component['package'] + marker + extension in names for marker in component['markers']),
                        'missing real archive contents: ' + suffix)
                if suffix == '.jar':
                    require(not any(n.startswith('BOOT-INF/') for n in names), 'bootable JAR is not a library')
                    embedded_pom = 'META-INF/maven/' + GROUP + '/' + artifact_id + '/pom.xml'
                    require(archive.read(embedded_pom) == pom.read_bytes(), 'embedded consumer POM differs')
                    require('META-INF/LICENSE' in names, 'missing distribution license')
                    class_names = [n for n in names if n.endswith('.class')]
                    require(all(n.startswith(component['package']) for n in class_names), 'foreign library classes')
                    classes = [archive.read(n) for n in class_names]
                    require(classes and all(int.from_bytes(c[6:8], 'big') == 65 for c in classes),
                            'library bytecode must target Java 21')
    destination = bundle / 'repository' / relative_path(version, component)
    destination.mkdir(parents=True)
    for suffix, source in sources.items():
        shutil.copyfile(source, destination / (stem + suffix))
    commit = run(['git', '-C', str(ROOT), 'rev-parse', 'HEAD'], capture_output=True, text=True).stdout.strip()
    dirty = bool(run(['git', '-C', str(ROOT), 'status', '--porcelain'], capture_output=True, text=True).stdout)
    write_identity(bundle, {'group': GROUP, 'artifact': artifact_id, 'version': version, 'commit': commit,
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
    if deployment_id:
        require(re.fullmatch('[a-fA-F0-9-]{36}', deployment_id), 'invalid deployment identifier')
        (bundle / 'central-deployment-id.txt').write_text(deployment_id + '\n')
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
        name = metadata['artifact'] + '-' + metadata['version'] + '-' + digest((bundle / 'identity.json').read_bytes())
        url = PORTAL + '/upload?' + urllib.parse.urlencode({'name': name, 'publishingType': 'USER_MANAGED'})
        deployment_id = request(url, 'POST', body, {**headers, 'Content-Type': 'multipart/form-data; boundary=' + boundary}).decode().strip()
        require(re.fullmatch('[a-fA-F0-9-]{36}', deployment_id), 'invalid deployment identifier')
        (bundle / 'central-deployment-id.txt').write_text(deployment_id + '\n')
    require(re.fullmatch('[a-fA-F0-9-]{36}', deployment_id), 'invalid deployment identifier')
    expected_name = metadata['artifact'] + '-' + metadata['version'] + '-' + digest((bundle / 'identity.json').read_bytes())
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


def configure_proxy(settings):
    """Keep the fresh-cache consumer usable where outbound HTTPS needs a proxy."""
    value = os.environ.get('https_proxy') or os.environ.get('HTTPS_PROXY')
    if not value:
        return
    proxy = urllib.parse.urlparse(value)
    require(proxy.scheme in ('http', 'https') and proxy.hostname and not proxy.query and not proxy.fragment
            and proxy.path in ('', '/'), 'invalid HTTPS proxy configuration')
    element = ET.SubElement(ET.SubElement(settings, 'proxies'), 'proxy')
    fields = [('id', 'consumer-transport'), ('active', 'true'), ('protocol', proxy.scheme),
              ('host', proxy.hostname), ('port', str(proxy.port or (443 if proxy.scheme == 'https' else 80)))]
    if proxy.username is not None:
        fields.append(('username', urllib.parse.unquote(proxy.username)))
    if proxy.password is not None:
        fields.append(('password', urllib.parse.unquote(proxy.password)))
    for name, text in fields:
        ET.SubElement(element, name).text = text


def consume(bundle, repository):
    metadata = read_bundle(bundle)
    component = component_for(metadata)
    require(repository in ('local', 'central', 'github'), 'unknown consumer repository')
    url = {'local': (bundle / 'repository').resolve().as_uri(), 'central': CENTRAL, 'github': GITHUB}[repository]
    if repository != 'local':
        require(not compare_remote(bundle, url, authorization('github') if repository == 'github' else None), 'remote bundle incomplete')
    for fixture in component['fixtures']:
        consume_fixture(metadata, component, fixture, repository, url)


def populate_examples(component, fixture, project):
    """Compile the actual guide sources rather than a second copy of the examples."""
    if component == COMPONENTS['configuration']:
        guide = (ROOT / 'docs/guides/configuration-library-check.md').read_text()
        destination = project / 'src/main/java/examples/configuration'
        destination.mkdir(parents=True, exist_ok=True)
        for language, path in [('java', destination / 'CollectorConfigurationCheck.java'),
                               ('yaml', project / 'collector.yml')]:
            blocks = re.findall(r'```' + language + r'\n(.*?)\n```', guide, re.DOTALL)
            require(len(blocks) == 1, 'guide example count changed')
            path.write_text(blocks[0])
        shutil.copyfile(ROOT / 'bootstrap/ioc-app/src/test/resources/consumer/configuration/ConfigurationRecipeMain.java',
                        destination / 'ConfigurationRecipeMain.java')
        resources = project / 'src/main/resources'
        resources.mkdir(parents=True, exist_ok=True)
        (resources / 'application.yml').write_text('spring.config.import: missing:ambient-secret\n')
        return
    if component != COMPONENTS['logging']:
        return
    guides = ['logging-library.md']
    if fixture == 'logging-boot-consumer':
        guides.append('logging-library-boot.md')
        driver = ROOT / 'bootstrap/ioc-app/src/test/resources/consumer/logging/BootRecipeMain.java'
        destination = project / 'src/main/java/qualification/BootRecipeMain.java'
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(driver, destination)
    for guide in guides:
        document = (ROOT / 'docs/guides' / guide).read_text()
        blocks = re.findall(r'```java\n(.*?)\n```', document, re.DOTALL)
        require(len(blocks) == (3 if guide == 'logging-library.md' else 1), 'guide Java example count changed')
        for source in blocks:
            package = re.search(r'^package ([A-Za-z_]\w*(?:\.[A-Za-z_]\w*)*);', source, re.MULTILINE)
            name = re.search(r'public final class (\w+)', source)
            require(package is not None and name is not None, 'incomplete guide Java example')
            path = project / 'src/main/java' / package.group(1).replace('.', '/') / (name.group(1) + '.java')
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(source)
        if guide == 'logging-library-boot.md':
            resources = project / 'src/main/resources/recipe'
            resources.mkdir(parents=True, exist_ok=True)
            for language, filename in [('yaml', 'application.yml'), ('xml', 'logback-spring.xml')]:
                blocks = re.findall(r'```' + language + r'\n(.*?)\n```', document, re.DOTALL)
                require(len(blocks) == 1, 'guide configuration count changed')
                (resources / filename).write_text(blocks[0])


def verify_resolved_artifacts(metadata, component, cache):
    resolved = cache / relative_path(metadata['version'], component)
    origins = (resolved / '_remote.repositories').read_text().splitlines()
    for suffix in SUFFIXES:
        name = metadata['artifact'] + '-' + metadata['version'] + suffix
        require(digest((resolved / name).read_bytes()) == metadata['files'][str(relative_path(metadata['version'], component) / name)],
                'consumer resolved different artifact bytes')
        # Maven 3.9.9 Resolver records the repository ID per downloaded file.
        # A Central fallback must not masquerade as a successful GitHub consumer.
        sources = {line.split('>', 1)[1].split('=', 1)[0] for line in origins if line.startswith(name + '>')}
        require(sources == {'library-target'}, 'consumer artifact did not resolve from selected repository')


def consumer_settings(work, repository, url, public_url=CENTRAL):
    """Pin every repository route; Resolver 1.9.22 abstains without a rule file."""
    settings = ET.Element('settings', xmlns='http://maven.apache.org/SETTINGS/1.2.0')
    configure_proxy(settings)
    mirrors = ET.SubElement(settings, 'mirrors')
    routes = [('library-target', 'central', url),
              ('public-dependencies', 'public-dependencies', public_url),
              ('public-plugins', 'public-plugins', CENTRAL),
              ('public-fallback', '*,!central,!public-dependencies,!public-plugins', CENTRAL)]
    rules = work / 'repository-filters'
    rules.mkdir()
    lines = (ROOT / 'tools/ci/library-publication-groups.txt').read_text().splitlines()
    allowed = {line.strip() for line in lines if line.strip() and not line.lstrip().startswith('#')}
    require(not any(g == GROUP or g.startswith((GROUP + '.', 'com.iocextractor')) for g in allowed),
            'own groups cannot enter the public repository allowlist')
    groups = '\n'.join(sorted(allowed)) + '\n'
    target_groups = GROUP + '\n'
    if repository == 'central' and url == CENTRAL:
        # Resolver 1.9.22 groups downloads by URL, even across repository IDs.
        # The selected Central endpoint also serves the reviewed public groups.
        target_groups += groups
    for identifier, match, target in routes:
        mirror = ET.SubElement(mirrors, 'mirror')
        for name, value in [('id', identifier), ('mirrorOf', match), ('url', target)]:
            ET.SubElement(mirror, name).text = value
        (rules / ('groupId-' + identifier + '.txt')).write_text(
            target_groups if identifier == 'library-target' else groups)
    # Also cover the pre-mirror IDs used in the standalone POMs.
    (rules / 'groupId-central.txt').write_text(target_groups)
    if repository == 'github':
        server = ET.SubElement(ET.SubElement(settings, 'servers'), 'server')
        for name, value in [('id', 'library-target'), ('username', '${env.GITHUB_ACTOR}'), ('password', '${env.GITHUB_TOKEN}')]:
            ET.SubElement(server, name).text = value
    path = work / 'settings.xml'
    ET.ElementTree(settings).write(path, encoding='UTF-8', xml_declaration=True)
    (work / 'global-settings.xml').write_text('<settings/>')
    return [str(ROOT / 'mvnw'), '-B', '-ntp', '-s', str(path), '-gs', str(work / 'global-settings.xml'),
            '-Dmaven.repo.local=' + str(work / 'cache'),
            '-Daether.remoteRepositoryFilter.groupId=true',
            '-Daether.remoteRepositoryFilter.groupId.basedir=' + str(rules)]


def consumer_environment():
    env = {k: v for k, v in os.environ.items() if not k.startswith(('GPG_', 'MAVEN_CENTRAL_'))}
    for name in ('MAVEN_ARGS', 'MAVEN_OPTS', 'JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS'):
        env.pop(name, None)
    return env


def consume_fixture(metadata, component, fixture, repository, url):
    with tempfile.TemporaryDirectory(prefix='ioc-library-consumer-') as folder:
        work = Path(folder)
        shutil.copytree(ROOT / 'tools/fixtures' / fixture, work / 'consumer')
        populate_examples(component, fixture, work / 'consumer')
        # Fresh caches and empty user/global settings prevent reactor-local or
        # developer configuration from masking a missing published dependency.
        command = consumer_settings(work, repository, url) + ['-Dlibrary.version=' + metadata['version']]
        env = consumer_environment()
        env['COLLECTOR_LOG_FILE'] = str(work / 'collector.ecs.json')
        run(command + ['compile', 'org.codehaus.mojo:exec-maven-plugin:3.5.0:java'], cwd=work / 'consumer', env=env)
        verify_resolved_artifacts(metadata, component, work / 'cache')
        if fixture == 'logging-consumer':
            negative_dependency_checks(command, work / 'consumer', env)
        if fixture == 'configuration-consumer':
            qualify_configuration(command, work / 'consumer', env)
            verify_resolved_artifacts(metadata, component, work / 'cache')
            fallback = work / 'alternate-repository'
            target = fallback / relative_path(metadata['version'], component)
            target.mkdir(parents=True)
            for suffix in SUFFIXES:
                name = metadata['artifact'] + '-' + metadata['version'] + suffix
                shutil.copyfile(work / 'cache' / relative_path(metadata['version'], component) / name, target / name)
            qualify_repository_routing(metadata, component, work / 'consumer', fallback.as_uri())
        print('Qualified ' + metadata['artifact'] + ':' + metadata['version'] + ' from ' + repository +
              ' (' + fixture + '); four resolved artifacts match the bundle and selected repository')


def negative_dependency_checks(command, project, env):
    """A warmed cache must not hide a missing or unresolvable required API."""
    for profile, diagnostic in [('exclude-slf4j', 'COMPILATION ERROR'),
                                ('unavailable-slf4j', 'Could not resolve dependencies')]:
        shutil.rmtree(project / 'target')
        result = subprocess.run(command + ['-o', '-P' + profile, 'compile'], cwd=project, env=env,
                                capture_output=True, text=True, timeout=120)
        output = result.stdout + result.stderr
        require(result.returncode != 0 and diagnostic in output and 'org.slf4j' in output,
                'negative SLF4J dependency check did not fail for the expected reason: ' + profile)
        print('Rejected missing/unavailable SLF4J API: ' + profile)


def configuration_graph(project, managed=False):
    """Review the whole resolved application graph, including the YAML recipe."""
    expected = {
        ('org.springframework.boot', 'spring-boot'): '4.0.8',
        **{('org.springframework', 'spring-' + name): '7.0.9'
           for name in ('core', 'context', 'aop', 'beans', 'expression')},
        **{('io.micrometer', 'micrometer-' + name): '1.16.7' for name in ('observation', 'commons')},
        ('commons-logging', 'commons-logging'): '1.3.6' if managed else '1.3.5',
        ('org.jspecify', 'jspecify'): '1.0.1' if managed else '1.0.0',
        ('org.yaml', 'snakeyaml'): '2.6',
    }
    actual = {}
    def visit(node):
        if node['groupId'] not in (GROUP, 'example.consumer'):
            key = node['groupId'], node['artifactId']
            require(key not in actual or actual[key] == node['version'], 'conflicting configuration dependency versions')
            actual[key] = node['version']
        for child in node.get('children', []):
            visit(child)
    visit(json.loads((project / 'target/dependencies.json').read_text()))
    require(actual == expected, 'configuration dependency closure changed; review before qualification')
    print('Reviewed configuration closure: ' + json.dumps({':'.join(k): v for k, v in sorted(actual.items())}))


def qualify_configuration(command, project, env):
    dependency = 'org.apache.maven.plugins:maven-dependency-plugin:3.11.0:'
    for profile in ([], ['-Papplication-bom']):
        run(command + profile + [dependency + 'tree', '-DoutputType=json', '-DoutputFile=target/dependencies.json',
                                 dependency + 'build-classpath', '-Dmdep.outputFile=target/classpath.txt'], cwd=project, env=env)
        configuration_graph(project, managed=bool(profile))
        if profile:
            run(command + profile + ['compile', 'org.codehaus.mojo:exec-maven-plugin:3.5.0:java'], cwd=project, env=env)
        classpath = str(project / 'target/classes') + os.pathsep + (project / 'target/classpath.txt').read_text().strip()
        require(str(ROOT) + '/' not in classpath, 'reactor path entered the standalone consumer classpath')
        recipe_env = {**env, 'COLLECTOR_REQUESTTIMEOUT': 'invalid-ambient-secret',
                      'SPRING_APPLICATION_JSON': '{"collector":{"request-timeout":"ambient-secret"}}'}
        java = str(Path(env['JAVA_HOME']) / 'bin/java') if env.get('JAVA_HOME') else 'java'
        result = subprocess.run([java,
                                 '-Dcollector.request-timeout=invalid-ambient-secret',
                                 '-Dspring.main.sources=ambient-secret', '-cp', classpath,
                                 'examples.configuration.ConfigurationRecipeMain', str(project)],
                                cwd=project, env=recipe_env, capture_output=True, text=True, timeout=25)
        require(result.returncode == 0 and 'RECIPE_VALIDATED' in result.stdout
                and not (project / 'runtime-marker').exists(), 'packaged closed-input guide failed')
        print('Closed-input guide passed from packaged JARs' + (' with application BOM' if profile else ''))
    # These failures must identify mediation, rather than an unrelated tool or network error.
    for profile, offline, diagnostic in [('incompatible-spring', [], 'Require upper bound dependencies error for org.springframework:spring-core:5.3.39'),
                                         ('unavailable-boot', ['-o'], '0.0.0-c4-unavailable')]:
        result = subprocess.run(command + offline + ['-P' + profile, 'validate'], cwd=project, env=env,
                                capture_output=True, text=True, timeout=180)
        output = result.stdout + result.stderr
        require(result.returncode != 0 and diagnostic in output and 'org.springframework' in output,
                'configuration mediation check did not fail for the expected reason: ' + profile)
        print('Rejected configuration dependency mediation: ' + profile)


def qualify_repository_routing(metadata, component, project, fallback_url):
    """A real alternate repository can supply the JAR only with filtering disabled."""
    for filtering in (True, False):
        with tempfile.TemporaryDirectory(prefix='ioc-library-routing-') as folder:
            work = Path(folder)
            empty = work / 'empty-target'
            empty.mkdir()
            shutil.copytree(project, work / 'consumer', ignore=shutil.ignore_patterns('target'))
            pom = work / 'consumer/pom.xml'
            pom.write_text(pom.read_text().replace('</repositories>', '<repository>'
                           '<id>public-thirdparty</id><url>' + CENTRAL + '</url></repository></repositories>', 1)
                           .replace('<snapshots><enabled>false</enabled></snapshots>',
                                    '<snapshots><enabled>true</enabled></snapshots>'))
            command = consumer_settings(work, 'local', empty.as_uri(), fallback_url)
            if not filtering:
                command.append('-Daether.remoteRepositoryFilter.groupId=false')
            require(not (work / 'cache').exists(), 'routing probe cache must start empty')
            result = subprocess.run(command + ['-Dlibrary.version=' + metadata['version'], 'compile'],
                                    cwd=work / 'consumer', env=consumer_environment(),
                                    capture_output=True, text=True, timeout=300)
            output = result.stdout + result.stderr
            resolved = work / 'cache' / relative_path(metadata['version'], component)
            if filtering:
                require(result.returncode != 0 and 'Could not resolve dependencies' in output
                        and metadata['artifact'] in output and not list(resolved.glob('*.jar')),
                        'missing selected artifact did not fail closed')
                print('Rejected missing selected artifact with a populated alternate repository and empty cache')
            else:
                require(result.returncode == 0, 'unfiltered alternate-repository control failed')
                origins = (resolved / '_remote.repositories').read_text()
                for suffix in SUFFIXES:
                    name = metadata['artifact'] + '-' + metadata['version'] + suffix
                    require(name + '>public-dependencies=' in origins, 'alternate-repository control used another origin')
                    require(digest((resolved / name).read_bytes()) == metadata['files'][str(
                        relative_path(metadata['version'], component) / name)], 'alternate control bytes differ')
                print('Unfiltered control resolved the same four artifacts from the alternate repository')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=('module', 'artifact', 'prepare', 'check', 'sign', 'central', 'github', 'consume'))
    parser.add_argument('--component', choices=tuple(COMPONENTS), default='concurrency')
    parser.add_argument('--bundle', type=Path)
    parser.add_argument('--version')
    parser.add_argument('--commit')
    parser.add_argument('--repository', default='local')
    parser.add_argument('--publish', action='store_true')
    parser.add_argument('--deployment-id', default='')
    args = parser.parse_args()
    component = COMPONENTS[args.component]
    if args.command in ('module', 'artifact'):
        print(component[args.command])
        return
    require(args.bundle is not None, '--bundle is required')
    bundle = args.bundle.resolve()
    if args.command == 'prepare':
        prepare(bundle, args.version or '', component)
        return
    metadata = read_bundle(bundle, expected_component=component)
    if args.command == 'check':
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
