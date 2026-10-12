#!/usr/bin/env python3
"""One frozen optimization point, four real daemon cells; raw evidence stays local."""
import argparse
import importlib.util
from pathlib import Path
import shutil
import sys
import time
import json
import urllib.request
import urllib.error

sys.dont_write_bytecode = True
SPEC = importlib.util.spec_from_file_location('journal', Path(__file__).with_name('typed-journal-comparison.py'))
J = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(J)
CAP, C5, ACK = J.CAP, J.C5, J.ACK


class GrowthWindow(J.JournalWindow):
    backend = 'staged'
    resource_profile = 'legacy'

    def __init__(self, root, port):
        super().__init__(root, port)
        self.http_timeout_seconds = 300 if self.resource_profile == 'reference-8g' else 40

    def physical_evidence(self):
        if self.backend == 'native':
            return {'scope': 'Native canonical tables; staged versions and transfer journal are not applicable'}
        return J.physical_evidence(self.root)

    def begin(self):
        self.debt_before = self.request('maintenance-evidence') if getattr(self, 'cleanup_census', False) else None
        self.before = self.physical_evidence()
        C5.QualificationWindow.begin(self)
        self.bulk_started = time.monotonic()

    def request(self, path):
        if not path.startswith(('receipt-read-burst', 'receipt-families', 'maintenance-evidence')):
            return super().request(path)
        # Full scans of 1m payloads are post-publication diagnostics, not a 40-second control probe.
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
        request = urllib.request.Request(f'http://127.0.0.1:{self.port}/qualification/' + path, data=b'', method='POST')
        try:
            with opener.open(request, timeout=900) as response:
                return json.load(response)
        except urllib.error.HTTPError as failure:
            with failure:
                body = failure.read(65537)
            if len(body) > 65536:
                raise RuntimeError('Qualification HTTP diagnostic exceeds 64 KiB') from failure
            raise RuntimeError(f'Qualification HTTP {failure.code}: {body.decode("utf-8", errors="replace")}') from failure

    def failure_summary(self):
        if self.result is not None:
            return dict(self.result, status='INCOMPLETE_POST_PUBLICATION',
                        scope='Successful bulk counters retained; failed post-publication diagnostics do not pass qualification')
        return super().failure_summary()

    def finish(self):
        value = C5.QualificationWindow.finish(self)
        value.update(bulk_started_monotonic=self.bulk_started, bulk_finished_monotonic=time.monotonic())
        value.update(physical_before=self.before, physical_after=self.physical_evidence(),
                     storage_before_cleanup=self.storage())
        if getattr(self, 'cleanup_census', False):
            value['cleanup_debt_before_workload'] = self.debt_before
            value['cleanup_debt_before_extra_pages'] = self.request('maintenance-evidence')
        reads = self.request('projection-read-burst?count=100')
        value['reads_after_publication'] = dict(reads, estimator='nearest rank',
                p95_ns=ACK.percentile(reads['samplesNanos'], .95), p99_ns=ACK.percentile(reads['samplesNanos'], .99))
        value['bounded_cleanup'] = [self.request('maintenance') for _ in range(96)]
        value.update(physical_after_cleanup=self.physical_evidence(), storage_after_cleanup=self.storage())
        reads = self.request('receipt-read-burst?count=1')
        value['receipt_read_after'] = reads
        value['receipt_families_after'] = (self.request('receipt-families') if self.backend == 'staged'
                                         else {'scope': 'Staged physical-family census is not applicable to native; logical receipt read and canonical census still verified'})
        if getattr(self, 'cleanup_census', False):
            value['cleanup_debt_after'] = self.request('maintenance-evidence')
        if getattr(self, 'separate_controls', False):
            value['separate_controls_after'] = [dict(error=self.request('pure-error'), ack=self.request('pure-ack')) for _ in range(20)]
        value['post_publication_finished_monotonic'] = time.monotonic()
        value['memory_sampling_scope'] = 'bulk plus post-publication diagnostics; bulk peaks also filtered by monotonic boundary'
        return value


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--runtime', type=Path, required=True)
    parser.add_argument('--point', type=int, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--config', type=Path, default=C5.REPO / 'docs/worknote/0.3.0/data-processing-routing/qualification/capacity/cap-7c-c5-policy.yml')
    parser.add_argument('--timeout', type=int, default=3600)
    parser.add_argument('--port', type=int, default=18211)
    parser.add_argument('--rows', type=int, nargs='+', default=[100000, 1000000])
    parser.add_argument('--resume', action='store_true', help='Retain failed/interrupted cells and skip completed passing cells of the same frozen runtime/envelope')
    parser.add_argument('--payloads', choices=('versioned', 'append'), default='versioned')
    parser.add_argument('--backend', choices=('native', 'staged'), default='staged')
    parser.add_argument('--resource-profile', choices=tuple(CAP.RESOURCE_PROFILES), default='legacy')
    parser.add_argument('--profiles', choices=('unique-document', 'collapse-import'), nargs='+',
                        default=['unique-document', 'collapse-import'])
    parser.add_argument('--smb', action='store_true')
    parser.add_argument('--environment', type=Path, help='systemd EnvironmentFile; secrets are never reported')
    parser.add_argument('--transport-config', type=Path, help='Existing stand YAML; only sync is copied into the private policy')
    parser.add_argument('--document-count', type=int, default=1, help='Deterministic DOCX corpus, still one active document at a time')
    parser.add_argument('--batch-export-quiet-seconds', type=int, help='Declared export cadence for batch coalescing; production settings unchanged')
    parser.add_argument('--sqlite-tuning', choices=('low-memory', 'balanced', 'high-throughput'),
                        help='Existing SQLite preset; applied equally to both canonical/service stores')
    parser.add_argument('--workspace-bytes', type=int)
    parser.add_argument('--total-disk-bytes', type=int)
    args = parser.parse_args()
    counts = tuple(args.rows)
    if args.point not in range(1, 9) or not counts or any(count < 1 for count in counts):
        parser.error('Use point 1..8 and positive workload sizes')
    if args.smb and (args.profiles != ['unique-document'] or not args.environment or not args.transport_config):
        parser.error('SMB qualification requires the document profile, EnvironmentFile and transport-config')
    if not 1 <= args.document_count <= min(1000, min(counts)) or (args.batch_export_quiet_seconds is not None and args.batch_export_quiet_seconds < 1):
        parser.error('Use 1..min(inputs,1000) documents and a positive batch quiet period')
    output = args.output.resolve()
    if not output.is_relative_to(C5.REPO / '.dev') or output.exists() != args.resume:
        parser.error('Use a new .dev evidence directory, or --resume with an existing report')
    output.mkdir(parents=True, exist_ok=args.resume)
    jar = args.runtime.resolve() / 'ioc-app.jar'
    report = dict(point=args.point, source=ACK.source_identity(), binary_sha256=CAP.BASE.digest(jar),
                  policy_sha256=CAP.BASE.digest(args.config), scope='full local daemon through projections and immutable slices; no SMB',
                  resource_acceptance='OPEN', post_scan_http_timeout_seconds=900, payloads=args.payloads, status='RUNNING', samples=[])
    report.update(backend=args.backend, resource_profile=args.resource_profile,
                  requested_profiles=args.profiles, requested_input_counts=counts,
                  runtime_envelope=CAP.RESOURCE_PROFILES[args.resource_profile])
    report.update(smb=args.smb, transport_policy_sha256=CAP.BASE.digest(args.transport_config) if args.smb else None,
                  scope='full local daemon plus actual SMB fetch/publish/readback in an owned namespace' if args.smb else report['scope'])
    report.update(document_count=args.document_count, batch_export_quiet_seconds=args.batch_export_quiet_seconds)
    report.update(sqlite_tuning=args.sqlite_tuning, control_http_timeout_seconds=300 if args.resource_profile == 'reference-8g' else 40)
    report['driver_sha256'] = {name: CAP.BASE.digest(Path(__file__).with_name(name)) for name in (
        'hybrid-growth-qualification.py', 'typed-journal-comparison.py', 'projection-ack-comparison.py',
        'sqlite-publication-capacity.py', 'service-capacity.py', 'service-capacity-inputs.py')}
    report['diagnostic_workspace_envelope'] = dict(workspace_bytes=args.workspace_bytes, total_disk_bytes=args.total_disk_bytes,
            scope='Explicit private profile only; CPU/JVM/cgroup limits and production configuration unchanged')
    if args.resume:
        previous = json.loads((output / 'report.json').read_text())
        for field, default in [('backend', 'staged'), ('resource_profile', 'legacy')]:
            if previous.get(field, default) != report[field]:
                parser.error('Resume would change frozen measurement identity: ' + field)
        for field, default in [('smb', False), ('transport_policy_sha256', None)]:
            if previous.get(field, default) != report[field]:
                parser.error('Resume would change frozen transport identity: ' + field)
        for field, default in [('document_count', 1), ('batch_export_quiet_seconds', None)]:
            if previous.get(field, default) != report[field]:
                parser.error('Resume would change frozen corpus/cadence identity: ' + field)
        if previous.get('sqlite_tuning') != args.sqlite_tuning:
            parser.error('Resume would change SQLite tuning identity')
        for field in ('point', 'binary_sha256', 'payloads', 'policy_sha256', 'diagnostic_workspace_envelope'):
            if previous.get(field) != report[field]:
                parser.error('Resume would change frozen measurement identity: ' + field)
        if any(s['status'] == 'RUNNING' for s in previous['samples']):
            parser.error('Dispose the interrupted owned service and record its failure before resuming')
        report['samples'] = previous['samples']
        report['resumptions'] = previous.get('resumptions', []) + [dict(previous_source=previous['source'],
                previous_driver_sha256=previous['driver_sha256'], resumed_epoch_seconds=time.time(),
                scope='Explicit collector repair; historical failures retained; no automatic retry')]
    CAP.save(output / 'report.json', report)
    try:
        report['clock_preflight'] = C5.clock_preflight(output)
        if report['clock_preflight']['status'] != 'OBSERVED_STABLE':
            raise RuntimeError('Unstable host UTC; no service fork admitted')
        with ACK.runtime_directory(report) as runtime:
            cp, agent = C5.freeze_runtime(jar, runtime)
            # Helpers belong to this frozen point, not whichever source is currently open.
            for package, prefix in [('com/iocextractor/bootstrap', 'SqliteCapacityApplication'),
                    ('com/iocextractor/bootstrap', 'SqliteCapacityMetrics'),
                    ('com/iocextractor/bootstrap', 'ReceiptPayloadDiagnostics'),
                    ('com/iocextractor/adapter/out/store/jdbc', 'StagedCapacityBundle')]:
                for source in (args.runtime / 'helpers' / package).glob(prefix + '*.class'):
                    shutil.copyfile(source, runtime / 'classes' / source.relative_to(args.runtime / 'helpers'))
            report['frozen_helpers'] = {str(p.relative_to(runtime)): CAP.BASE.digest(p) for p in sorted((runtime / 'classes').rglob('*.class'))}
            if args.resume and report['frozen_helpers'] != previous['frozen_helpers']:
                raise RuntimeError('Frozen helper identity changed on resume')
            original = CAP.yaml.safe_load(args.config.read_text())
            for count in counts:
                for profile in args.profiles:
                    if any(s['status'] == 'CORRECTNESS_PASS' and s.get('rows') == count and s.get('profile') == profile
                           for s in report['samples']):
                        continue
                    policy = CAP.yaml.safe_load(CAP.yaml.safe_dump(original))
                    if args.smb:
                        policy['ioc']['sync'] = CAP.yaml.safe_load(args.transport_config.read_text())['ioc']['sync']
                        declared = {p['name'] for p in policy['ioc']['export']['profiles']}
                        supplied = {t['export-profile'] for t in policy['ioc']['sync']['publish']['targets']}
                        if declared != supplied:
                            raise RuntimeError('Stand transport must cover exactly the qualified export profiles')
                    if args.batch_export_quiet_seconds:
                        policy['ioc']['export']['trigger'].update(**{
                            'quiet-period': str(args.batch_export_quiet_seconds) + 's', 'max-cap': '30m'})
                    if args.sqlite_tuning:
                        for role in ('service', 'dataframe'):
                            policy['ioc']['storage'][role]['sqlite']['tuning'] = args.sqlite_tuning
                    policy['ioc'].setdefault('processing', {}).setdefault('workspace', {}).update(
                        **{'maximum-row-bytes': 16384, 'maximum-field-bytes': 4096, 'cache-kib': 1024})
                    for key, supplied in [('workspace-bytes', args.workspace_bytes), ('total-disk-bytes', args.total_disk_bytes)]:
                        if supplied is not None:
                            policy['ioc']['processing']['workspace'][key] = supplied
                    args.import_artifact = 'masks' if profile == 'collapse-import' else None
                    args.processed_import = bool(args.import_artifact)
                    args.shape = 'collapse' if args.import_artifact else 'unique'
                    if args.import_artifact:
                        C5.import_policy(policy, args.import_artifact, True)
                        args.import_fixture = lambda *values: C5.processed_fixture(*values, 'collapse')
                    args.config = runtime / (profile + '.yml')
                    args.config.write_text(CAP.yaml.safe_dump(policy, sort_keys=False, allow_unicode=True))
                    args.document, args.diagnostic = None, False
                    args.separate_smb_completion = args.smb
                    args.format, args.initial_rows, args.rows = 'docx', 100, count
                    args.gate_sample, args.canonical_evidence = ACK.correctness_gate, J.canonical_evidence
                    args.sample_profile = profile
                    args.sample_checkpoint = lambda: CAP.save(output / 'report.json', report)
                    args.qualification = GrowthWindow
                    GrowthWindow.backend = args.backend
                    GrowthWindow.resource_profile = args.resource_profile
                    GrowthWindow.cleanup_census = args.point >= 6 and args.backend == 'staged'
                    GrowthWindow.separate_controls = args.point >= 8
                    args.launcher = ['-Dqualification.sqlite=' + args.backend, '-Dqualification.projection=direct',
                        '-Dqualification.journal=chunk', '-Dqualification.receipts=' + args.payloads, '-javaagent:' + str(agent),
                        '-cp', cp, 'com.iocextractor.bootstrap.SqliteCapacityApplication']
                    result = CAP.sample(args, jar, report, len(report['samples']), output)
                    result.update(profile=profile, rows=count)
                    CAP.save(output / 'report.json', report)
            complete = all(any(s['status'] == 'CORRECTNESS_PASS' and s.get('rows') == count and s.get('profile') == profile
                               for s in report['samples']) for count in counts for profile in args.profiles)
            report['matrix_status'] = 'COMPLETE' if complete else 'INCOMPLETE'
            report['status'] = ('COMPLETE_WITH_FAILURE_HISTORY' if any(s['status'] != 'CORRECTNESS_PASS' for s in report['samples'])
                                else 'COMPLETE') if complete else 'INCOMPLETE'
    except BaseException as failure:
        report.update(status='ERROR', failure=str(failure)); raise
    finally:
        CAP.save(output / 'report.json', report)
    if report.get('matrix_status') != 'COMPLETE':
        raise SystemExit(1)


if __name__ == '__main__':
    main()
