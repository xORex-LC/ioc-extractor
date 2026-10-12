#!/usr/bin/env python3
"""Bounded staged typed-journal comparison, with identical direct ACK and no ACK warmup prefix."""
import argparse
from contextlib import closing
import importlib.util
import json
from pathlib import Path
import shutil
import sqlite3
import sys
import time

sys.dont_write_bytecode = True
SPEC = importlib.util.spec_from_file_location('ack_comparison', Path(__file__).with_name('projection-ack-comparison.py'))
ACK = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(ACK)
C5, CAP = ACK.C5, ACK.CAP


def physical_evidence(root):
    """Separate complete typed effects from compact transfer pointers and bounded journal cleanup debt."""
    with closing(sqlite3.connect(f'file:{root / "var/db/ioc-dataframe.db"}?mode=ro', uri=True)) as db:
        db.execute('BEGIN')
        names = [row[0] for row in db.execute("SELECT name FROM sqlite_schema WHERE type='table' AND name LIKE 'publication_version_%'")]
        versions = {name: db.execute('SELECT COUNT(*) FROM "' + name.replace('"', '""') + '"').fetchone()[0] for name in names}
        return dict(typed_versions=versions, typed_version_rows=sum(versions.values()),
                    journal_pointer_rows=db.execute('SELECT COUNT(*) FROM publication_fact').fetchone()[0],
                    journal_chunk_rows=db.execute('SELECT COUNT(*) FROM publication_chunk').fetchone()[0],
                    committed_effects=db.execute("SELECT COALESCE(SUM(contributions),0) FROM publication_unit WHERE state='COMMITTED'").fetchone()[0],
                    unfinished_units=db.execute("SELECT COUNT(*) FROM publication_unit WHERE state IN('PENDING','SEALED')").fetchone()[0],
                    scope='Retained physical census, not lifetime insert counts; normal maintenance remains enabled')


def canonical_evidence(root, config):
    """Add import receipt diagnostics/slot evidence to the shared final identity/confirmation receipt oracle."""
    import hashlib
    result = C5.native_cost_evidence(root, config)
    queries = {
        'import_receipt': 'SELECT sequence_no,source_id,snapshot_sha256,snapshot_size,contract_id,contract_version,'
                          'contract_fingerprint,outcome,accepted_rows,rejected_rows,public_mutations FROM import_commit',
        'import_artifacts': 'SELECT artifact,public_mutation,deadline_changed,projection_generation FROM import_commit_artifact',
        'import_rejections': 'SELECT rejection_ordinal,source_row_number,artifact,diagnostic_code FROM import_row_rejection',
        'import_warnings': 'SELECT warning_ordinal,source_row_number,artifact,diagnostic_code FROM import_row_warning',
        'import_slots': 'SELECT resolution_ordinal,source_row_number,artifact,lifecycle_id,requested_slot,assigned_slot,outcome FROM import_slot_resolution',
    }
    with closing(sqlite3.connect(f'file:{root / "var/db/ioc-dataframe.db"}?mode=ro', uri=True)) as db:
        db.execute('BEGIN')
        for name, sql in queries.items():
            cursor = db.execute(sql + ' LIMIT 0')
            order = ','.join(str(index + 1) for index in range(len(cursor.description)))
            checksum, count = hashlib.sha256(), 0
            for row in db.execute(sql + ' ORDER BY ' + order):
                checksum.update(json.dumps(row, ensure_ascii=False, separators=(',', ':')).encode() + b'\n')
                count += 1
            result['deterministic_fingerprints'][name] = dict(rows=count, sha256=checksum.hexdigest())
    return result


class JournalWindow(C5.QualificationWindow):
    def __init__(self, root, port):
        super().__init__(root, port, maintenance_every=5)

    def request(self, path):
        return super().request('ack?pendingOnly=true' if path == 'control' else path)

    def begin(self):
        self.before = physical_evidence(self.root)
        super().begin()
        self.bulk_started = time.monotonic()

    def storage(self):
        result = CAP.named_storage(self.root)
        result.update({name + '_db_bytes': (self.root / f'var/db/ioc-{name}.db').stat().st_size
                       for name in ('dataframe', 'service')})
        return result

    def finish(self):
        result = super().finish()
        result.update(bulk_started_monotonic=self.bulk_started, bulk_finished_monotonic=time.monotonic())
        result.update(physical_before=self.before, physical_after=physical_evidence(self.root),
                      storage_before_cleanup=self.storage())
        reads = self.request('projection-read-burst?count=100')
        result['reads_after_publication'] = dict(reads, estimator='nearest rank',
                p95_ns=ACK.percentile(reads['samplesNanos'], .95), p99_ns=ACK.percentile(reads['samplesNanos'], .99))
        # Same finite budget, after the bulk window; no sustained-retention or throughput claim.
        cleanup = [self.request('maintenance') for _ in range(96)]
        result.update(bounded_cleanup=cleanup, physical_after_cleanup=physical_evidence(self.root),
                      storage_after_cleanup=self.storage())
        return result


def compare_pair(members, dimension="journal", variants=("row", "chunk")):
    if len(members) != 2 or {item[dimension] for item in members} != set(variants):
        raise RuntimeError('Incomplete row/chunk pair')
    if any(item['status'] != 'CORRECTNESS_PASS' for item in members):
        return dict(status='INCOMPLETE', failure='Failed window retained; no successful-cost ratio')
    row = next(item for item in members if item[dimension] == variants[0])
    chunk = next(item for item in members if item[dimension] == variants[1])
    if row['input']['sha256'] != chunk['input']['sha256'] or row['canonical_oracle'] != chunk['canonical_oracle']:
        raise RuntimeError('Paired inputs or independent fields/keys/provenance differ')
    if row['canonical_evidence']['deterministic_fingerprints'] != chunk['canonical_evidence']['deterministic_fingerprints']:
        raise RuntimeError('Final identities or receipt payload/order differ')
    if dimension == 'payloads' and 'receipt_reads_after' in row['qualification']:
        for field in ('payloadSha256', 'rowsPerScan', 'receiptCount'):
            if row['qualification']['receipt_reads_after'][field] != chunk['qualification']['receipt_reads_after'][field]:
                raise RuntimeError('Real-port receipt replay differs: ' + field)
    return dict(status='COMPLETE', semantics='identical independent final fields/keys/provenance, identities and receipt payload/order',
                time_ratio=chunk['local_window_upper_seconds'] / row['local_window_upper_seconds'],
                allocations_ratio=chunk['qualification']['allocatedProcessBytes'] / row['qualification']['allocatedProcessBytes'])


def freeze_baseline(jar, folder, helpers):
    """Retain the old composition; both variants use the same current metric endpoints and agent."""
    cp, agent = C5.freeze_runtime(jar, folder)
    for package, prefix in [('com/iocextractor/bootstrap', 'SqliteCapacityApplication'),
                            ('com/iocextractor/adapter/out/store/jdbc', 'StagedCapacityBundle')]:
        paths = sorted((helpers / package).glob(prefix + '*.class'))
        if not paths:
            raise ValueError('Missing frozen baseline composition: ' + prefix)
        for source in paths:
            target = folder / 'classes' / source.relative_to(helpers)
            shutil.copyfile(source, target)
    return cp, agent


def main(experiment="journal", window=JournalWindow):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--baseline-jar', type=Path, required=True)
    parser.add_argument('--baseline-helpers', type=Path, required=True)
    parser.add_argument('--jar', type=Path, required=True)
    parser.add_argument('--config', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--rows', type=int, default=10000)
    parser.add_argument('--samples', type=int, default=3)
    parser.add_argument('--timeout', type=int, default=900)
    parser.add_argument('--port', type=int, default=18209)
    parser.add_argument('--baseline-only', action='store_true', help='Receipt attribution only; no before/after claim')
    args = parser.parse_args()
    if args.baseline_only and experiment != 'receipts':
        parser.error('Baseline-only is supported by the receipt experiment')
    variants = ('versioned', 'append') if experiment == 'receipts' else ('row', 'chunk')
    output = args.output.absolute()
    if not output.is_relative_to(C5.REPO / '.dev') or output.exists() or output.resolve() != output:
        parser.error('Use a new private .dev directory without symlinks')
    if min(args.rows, args.samples, args.timeout) < 1:
        parser.error('Positive workload/forks/timeout required')
    output.mkdir(parents=True)
    identity = ACK.source_identity()
    report = dict(source=identity, baseline_jar_sha256=CAP.BASE.digest(args.baseline_jar),
                  candidate_jar_sha256=CAP.BASE.digest(args.jar), policy_sha256=CAP.BASE.digest(args.config),
                  driver_sha256=CAP.BASE.digest(__file__), java=CAP.command(['java', '--version']),
                  scope=('whole local staged service; receipt payload representation only; compact journal and direct ACK in both variants'
                         if experiment == 'receipts' else 'whole local staged service; typed chunk journal only; direct ACK in both variants; no ACK prefix'),
                  resource_acceptance='OPEN', requested_pairs_per_profile=args.samples, rows=args.rows,
                  samples=[], comparisons=[], status='RUNNING')
    CAP.save(output / 'report.json', report)
    try:
        report['clock_preflight'] = C5.clock_preflight(output)
        if report['clock_preflight']['status'] != 'OBSERVED_STABLE':
            raise RuntimeError('Host clock preflight failed; no service fork admitted')
        with ACK.runtime_directory(report) as runtime:
            frozen = {}
            for mode, source in [(variants[0], args.baseline_jar), (variants[1], args.jar)]:
                folder = runtime / mode
                folder.mkdir()
                jar = folder / 'ioc-app.jar'
                shutil.copyfile(source, jar)
                cp, agent = freeze_baseline(jar, folder, args.baseline_helpers) if mode == variants[0] else C5.freeze_runtime(jar, folder)
                frozen[mode] = (jar, cp, agent)
            report['frozen_classes_sha256'] = {str(path.relative_to(runtime)): CAP.BASE.digest(path)
                    for path in sorted(runtime.rglob('*.class'))}
            original = CAP.yaml.safe_load(args.config.read_text())
            for profile in ('unique-document', 'collapse-import'):
                policy = CAP.yaml.safe_load(CAP.yaml.safe_dump(original))
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
                args.gate_sample, args.canonical_evidence = ACK.correctness_gate, canonical_evidence
                args.qualification = window
                for pair in range(args.samples):
                    for mode in ((variants[0],) if args.baseline_only else (variants if pair % 2 == 0 else variants[::-1])):
                        jar, cp, agent = frozen[mode]
                        args.launcher = ['-Dqualification.sqlite=staged', '-Dqualification.projection=direct',
                                         '-Dqualification.journal=' + ('chunk' if experiment == 'receipts' else mode),
                                         '-Dqualification.receipts=' + (mode if experiment == 'receipts' else 'versioned'), '-javaagent:' + str(agent), '-cp', cp,
                                         'com.iocextractor.bootstrap.SqliteCapacityApplication']
                        sample = CAP.sample(args, jar, report, len(report['samples']), output)
                        sample.update(journal='chunk' if experiment == 'receipts' else mode, profile=profile, pair=pair)
                        if experiment == 'receipts': sample['payloads'] = mode
                        CAP.save(output / 'report.json', report)
                    if args.baseline_only:
                        continue
                    comparison = compare_pair(report['samples'][-2:], 'payloads' if experiment == 'receipts' else 'journal', variants)
                    report['comparisons'].append(dict(comparison, profile=profile, pair=pair))
                    if comparison['status'] != 'COMPLETE':
                        break  # Retain failures, never retry a failed window.
        if ACK.source_identity() != identity:
            raise RuntimeError('Source changed during frozen qualification; retained evidence is diagnostic only')
        report['status'] = 'BASELINE_COMPLETE' if args.baseline_only and len(report['samples']) == args.samples * 2 and all(
                item['status'] == 'CORRECTNESS_PASS' for item in report['samples']) else 'COMPLETE' if len(report['comparisons']) == args.samples * 2 and all(
                item['status'] == 'COMPLETE' for item in report['comparisons']) else 'INCOMPLETE'
    except BaseException as failure:
        report.update(status='ERROR', failure=str(failure))
        raise
    finally:
        CAP.save(output / 'report.json', report)
    if report['status'] not in ('COMPLETE', 'BASELINE_COMPLETE'):
        raise SystemExit(1)


if __name__ == '__main__':
    main()
