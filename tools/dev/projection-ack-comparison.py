#!/usr/bin/env python3
"""One bounded ACK experiment: frozen prepared versus direct staged service; no resource acceptance."""
import argparse
from contextlib import contextmanager
import hashlib
import importlib.util
from pathlib import Path
import shutil
import sys
import tempfile

sys.dont_write_bytecode = True
SPEC = importlib.util.spec_from_file_location('sqlite_capacity', Path(__file__).with_name('sqlite-publication-capacity.py'))
C5 = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(C5)
CAP = C5.CAP


@contextmanager
def runtime_directory(report):
    """Observe cleanup after the directory owner exits, including failed qualification."""
    root = None
    try:
        with tempfile.TemporaryDirectory(prefix='ioc-ack-runtime-', dir=C5.REPO / '.dev') as temporary:
            root = Path(temporary)
            yield root
    finally:
        report['temporary_runtime_removed'] = root is not None and not root.exists()


def source_identity():
    """Record the complete reviewable worktree, including new sources; never call it an exact-HEAD build."""
    paths = CAP.command(['git', 'ls-files', '--cached', '--others', '--exclude-standard']).splitlines()
    checksum = hashlib.sha256()
    for name in sorted(set(paths)):
        path = C5.REPO / name
        checksum.update(name.encode() + b'\0')
        checksum.update(bytes.fromhex(CAP.BASE.digest(path)) if path.is_file() else b'deleted')
    return dict(head=CAP.command(['git', 'rev-parse', 'HEAD']), tree_sha256=checksum.hexdigest(),
                status=CAP.command(['git', 'status', '--porcelain']), scope='full tracked and unignored source worktree')


def percentile(values, fraction):
    """Declared nearest-rank estimator; no interpolation or statistical tail claim from whole-window medians."""
    import math
    if not values:
        raise ValueError('Missing percentile samples')
    return sorted(values)[max(0, math.ceil(len(values) * fraction) - 1)]


class AckWindow(C5.QualificationWindow):
    def __init__(self, root, port, retained, burst):
        super().__init__(root, port, maintenance_every=5)
        self.retained, self.burst = retained, burst
        self.alone = None

    def request(self, path):
        # Same contract in both variants: ACK pending coverage, reject late errors after installation.
        return super().request('ack?pendingOnly=true' if path == 'control' else path)

    def storage(self):
        value = CAP.named_storage(self.root)
        value.update({name + '_db_bytes': (self.root / f'var/db/ioc-{name}.db').stat().st_size
                      for name in ('dataframe', 'service')})
        return value

    def begin(self):
        before = self.request('projection-evidence')
        storage_before = self.storage()
        self.request('window/start')
        burst = self.request('ack-burst?count=' + str(self.burst))
        metrics = self.request('window/end')
        after = self.request('projection-evidence')
        samples = burst['samples']
        self.alone = dict(burst=burst, metrics=metrics, before=before, after=after,
                          storage_before=storage_before, storage_after=self.storage(),
                          estimator='nearest rank', sample_count=len(samples),
                          ack_p50_ns=percentile([item['eligibleToCompleteNanos'] for item in samples], .50),
                          ack_p95_ns=percentile([item['eligibleToCompleteNanos'] for item in samples], .95),
                          ack_p99_ns=percentile([item['eligibleToCompleteNanos'] for item in samples], .99),
                          read_p95_ns=percentile([item['readNanos'] for item in samples], .95),
                          read_p99_ns=percentile([item['readNanos'] for item in samples], .99))
        CAP.save(self.retained.with_suffix('.alone.json'), self.alone)
        super().begin()

    def finish(self):
        result = super().finish()
        result['controls_alone'] = self.alone
        result['physical_after_publication'] = self.request('projection-evidence')
        cleanup = []
        for _ in range(10):
            cleanup.append(self.request('maintenance'))
        result['bounded_cleanup'] = cleanup
        result['physical_after_cleanup'] = self.request('projection-evidence')
        result['storage_after_cleanup'] = self.storage()
        return result

    def close(self):
        super().close()
        raw = self.root / 'controls.jsonl'
        if raw.exists():
            shutil.copyfile(raw, self.retained.with_suffix('.controls.jsonl'))


def correctness_gate(sample, ignored_size):
    metrics = sample['qualification']
    if not metrics['admissionWindowMaximaExact'] or metrics['allocatedProcessBytes'] < 0:
        raise RuntimeError('Incomplete admission/allocation accounting')
    return dict(status='CORRECTNESS_PASS', resource_budget='OPEN; no numeric acceptance in this experiment')


def compare_pair(members):
    if len(members) != 2 or {item['projection'] for item in members} != {'prepared', 'direct'}:
        raise RuntimeError('Incomplete prepared/direct pair')
    if any(item['status'] != 'CORRECTNESS_PASS' for item in members):
        return dict(status='INCOMPLETE', failure='Failed window retained; no successful-cost ratio')
    prepared = next(item for item in members if item['projection'] == 'prepared')
    direct = next(item for item in members if item['projection'] == 'direct')
    if prepared['input']['sha256'] != direct['input']['sha256'] or prepared['canonical_oracle'] != direct['canonical_oracle']:
        raise RuntimeError('Paired inputs or independent fields/keys/provenance differ')
    return dict(status='COMPLETE', semantics='identical independent fields/keys/provenance and separately checked receipts/slots',
                time_ratio=direct['local_window_upper_seconds'] / prepared['local_window_upper_seconds'],
                allocations_ratio=direct['qualification']['allocatedProcessBytes'] / prepared['qualification']['allocatedProcessBytes'])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--baseline-jar', type=Path, required=True)
    parser.add_argument('--jar', type=Path, required=True)
    parser.add_argument('--config', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--rows', type=int, default=10000)
    parser.add_argument('--samples', type=int, default=3)
    parser.add_argument('--burst', type=int, default=200)
    parser.add_argument('--timeout', type=int, default=900)
    parser.add_argument('--port', type=int, default=18209)
    args = parser.parse_args()
    output = args.output.absolute()
    if not output.is_relative_to(C5.REPO / '.dev') or output.exists() or output.resolve() != output:
        parser.error('Use a new private .dev directory without symlinks')
    if min(args.rows, args.samples, args.timeout) < 1 or not 1 <= args.burst <= 500:
        parser.error('Positive workloads/forks/timeouts and 1..500 burst required')
    output.mkdir(parents=True)
    identity = source_identity()
    report = dict(source=identity, baseline_jar_sha256=CAP.BASE.digest(args.baseline_jar),
                  candidate_jar_sha256=CAP.BASE.digest(args.jar), policy_sha256=CAP.BASE.digest(args.config),
                  driver_sha256=CAP.BASE.digest(__file__), java=CAP.command(['java', '--version']),
                  scope='same versioned staged layout; projection ACK/error execution only; whole local service',
                  resource_acceptance='OPEN', requested_pairs_per_profile=args.samples, rows=args.rows,
                  burst=args.burst, samples=[], status='RUNNING')
    report['comparisons'] = []
    CAP.save(output / 'report.json', report)
    try:
        report['clock_preflight'] = C5.clock_preflight(output)
        if report['clock_preflight']['status'] != 'OBSERVED_STABLE':
            raise RuntimeError('Host clock preflight failed; no service fork admitted')
        with runtime_directory(report) as runtime:
            frozen = {}
            for mode, source in [('prepared', args.baseline_jar), ('direct', args.jar)]:
                folder = runtime / mode
                folder.mkdir()
                jar = folder / 'ioc-app.jar'
                shutil.copyfile(source, jar)
                cp, agent = C5.freeze_runtime(jar, folder)
                frozen[mode] = (jar, cp, agent)
            report['frozen_classes_sha256'] = {str(path.relative_to(runtime)): CAP.BASE.digest(path)
                for path in sorted(runtime.rglob('*.class'))}
            original_policy = CAP.yaml.safe_load(args.config.read_text())
            for profile in ('unique-document', 'collapse-import'):
                policy = CAP.yaml.safe_load(CAP.yaml.safe_dump(original_policy))
                policy['ioc'].setdefault('processing', {}).setdefault('workspace', {}).update(
                    **{'maximum-row-bytes': 16384, 'maximum-field-bytes': 4096, 'cache-kib': 1024})
                args.import_artifact = 'masks' if profile == 'collapse-import' else None
                args.processed_import = bool(args.import_artifact)
                args.shape = 'collapse' if args.import_artifact else 'unique'
                if args.import_artifact:
                    C5.import_policy(policy, args.import_artifact, True)
                    args.import_fixture = lambda *values: C5.processed_fixture(*values, args.shape)
                args.config = runtime / (profile + '.yml')
                args.config.write_text(CAP.yaml.safe_dump(policy, sort_keys=False, allow_unicode=True))
                args.smb, args.environment, args.document, args.diagnostic = False, None, None, False
                args.format, args.initial_rows = 'docx', 100
                args.gate_sample, args.canonical_evidence = correctness_gate, C5.canonical_evidence
                for pair in range(args.samples):
                    modes = ('prepared', 'direct') if pair % 2 == 0 else ('direct', 'prepared')
                    for mode in modes:
                        jar, cp, agent = frozen[mode]
                        index = len(report['samples'])
                        retained = output / f'{profile}-{pair}-{mode}'
                        args.qualification = lambda root, port: AckWindow(root, port, retained, args.burst)
                        args.launcher = ['-Dqualification.sqlite=staged', '-Dqualification.projection=' + mode,
                                         '-javaagent:' + str(agent), '-cp', cp,
                                         'com.iocextractor.bootstrap.SqliteCapacityApplication']
                        sample = CAP.sample(args, jar, report, index, output)
                        sample.update(projection=mode, profile=profile, pair=pair)
                        CAP.save(output / 'report.json', report)
                    members = report['samples'][-2:]
                    comparison = compare_pair(members)
                    comparison.update(profile=profile, pair=pair)
                    report['comparisons'].append(comparison)
                    if comparison['status'] != 'COMPLETE':
                        # Finish both variants, stop new pairs in this profile; never retry a failed window.
                        break
        if source_identity() != identity:
            raise RuntimeError('Source changed during frozen qualification; evidence remains diagnostic only')
        report['status'] = 'COMPLETE' if len(report['comparisons']) == args.samples * 2 and all(
            item['status'] == 'COMPLETE' for item in report['comparisons']) else 'INCOMPLETE'
    except BaseException as failure:
        report.update(status='ERROR', failure=str(failure))
        raise
    finally:
        CAP.save(output / 'report.json', report)
    if report['status'] != 'COMPLETE':
        raise SystemExit(1)


if __name__ == '__main__':
    main()
