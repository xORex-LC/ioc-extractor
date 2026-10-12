#!/usr/bin/env python3
"""Render only observed frozen cycle metrics; missing and failed windows remain explicit."""
import argparse
import json
import math
from pathlib import Path


def number(value, factor=1, digits=2):
    return '—' if value is None else f'{value / factor:.{digits}f}'


def bulk_peaks(folder, sample, end=None, start=None):
    end = end if end is not None else sample.get('timeline', {}).get('complete_monotonic')
    start = start if start is not None else sample.get('handoff', {}).get('monotonic')
    peaks = {}
    raw = folder / str(sample['index']) / 'resources.jsonl'
    if not raw.exists() or start is None or end is None:
        return peaks
    with raw.open() as stream:
        for line in stream:
            row = json.loads(line)
            if start <= row.get('monotonic', -1) <= end:
                for key in ('rss_bytes', 'heap_used_bytes', 'wal_bytes', 'workspace_bytes', 'reference_slice_current_bytes'):
                    if row.get(key) is not None:
                        peaks[key] = max(peaks.get(key, 0), row[key])
    return peaks


def reference_tables(root):
    """Report complete service windows separately from post-publication probes."""
    cells = []
    for folder in sorted(root.iterdir()):
        report_path = folder / 'report.json'
        if not report_path.is_file():
            continue
        report = json.loads(report_path.read_text())
        if report.get('resource_profile') != 'reference-8g':
            continue
        for sample in report.get('samples', []):
            cells.append((folder, report, sample))
    print('| Model | Profile / inputs / documents | Qualification | Complete service s | Inputs/s | Allocated GiB | Heap MiB | RSS MiB | Shared slice MiB | New completed slices / successful publications |')
    print('|' + '---|' * 10)
    for folder, report, sample in cells:
        elapsed = (sample.get('smb_from_producer_upload_upper_seconds') if report.get('smb')
                   else sample.get('raw_local_handoff_to_slices_seconds'))
        boundary = sample.get('handoff', {}).get('upload_started_monotonic') if report.get('smb') else sample.get('handoff', {}).get('monotonic')
        peaks = (bulk_peaks(folder, sample, boundary + elapsed, boundary)
                 if boundary is not None and elapsed is not None else {})
        before = sample.get('publication_counts_before', {})
        after = sample.get('publication_counts_at_service_completion', {})
        def count(table, status):
            if table not in before or table not in after:
                return '—'
            return sum(row['count'] for row in after[table] if row['status'] == status) - sum(row['count'] for row in before[table] if row['status'] == status)
        inputs = sample.get('rows')
        q = sample.get('qualification', sample.get('qualification_at_failure', {}))
        model = report['backend'] + (' (workers/window 1/1)' if report.get('single_document_execution') else '')
        print('| ' + ' | '.join([model, f"{sample.get('profile', 'pending')} / {inputs or '—'} / {report.get('document_count', 1)}",
            sample['status'], number(elapsed), number(inputs / elapsed if inputs and elapsed else None),
            number(q.get('allocatedProcessBytes'), 2**30), number(peaks.get('heap_used_bytes'), 2**20),
            number(peaks.get('rss_bytes'), 2**20), number(peaks.get('reference_slice_current_bytes'), 2**20),
            f"{count('export_run', 'COMPLETED')} / {count('publish_ledger', 'SUCCEEDED')}"]) + ' |')
    print('\nService timing includes configured stabilization/export cadence and service-side SMB verification, with producer upload for SMB. Independent oracle/readback, complete receipt replay and extra cleanup follow the service boundary. Failed windows remain unsuccessful qualification even if service timing was observed. One fork per cell is diagnostic; resource acceptance is separate. Shared-slice peaks include the collector and file cache; an absent historical counter remains absent.')
    print('\n| Model | Profile / inputs / documents | Max writer hold / control wait ms | Receipt replay s / rows | Extra cleanup s | Canonical DB / WAL MiB after cleanup |')
    print('|' + '---|' * 6)
    for folder, report, sample in cells:
        q = sample.get('qualification', sample.get('qualification_at_failure', {}))
        operations = q.get('writerOperations', {})
        hold = max((v.get('maximumHoldNanos', 0) for v in operations.values()), default=None)
        wait = operations.get('CONTROL', {}).get('maximumWaitNanos')
        reads = q.get('receipt_read_after', {})
        pages = q.get('bounded_cleanup', [])
        cleanup = sum(p['eligibleToCompleteNanos'] for p in pages) if pages else None
        storage = q.get('storage_after_cleanup', {})
        model = report['backend'] + (' (workers/window 1/1)' if report.get('single_document_execution') else '')
        print('| ' + ' | '.join([model, f"{sample.get('profile', 'pending')} / {sample.get('rows') or '—'} / {report.get('document_count', 1)}",
            number(hold, 1e6) + ' / ' + number(wait, 1e6),
            number(reads.get('samplesNanos', [None])[0], 1e9) + ' / ' + str(reads.get('rowsPerScan', '—')),
            number(cleanup, 1e9), number(storage.get('dataframe_db_bytes'), 2**20) + ' / ' + number(storage.get('canonical_wal_bytes'), 2**20)]) + ' |')
    matched = {}
    for folder, report, sample in cells:
        if sample['status'] == 'CORRECTNESS_PASS':
            key = (sample.get('profile'), sample.get('rows'), report.get('document_count', 1), report.get('smb'),
                   report.get('sqlite_tuning'), report.get('batch_export_quiet_seconds'), sample['input']['sha256'],
                   report.get('single_document_execution', False))
            matched.setdefault(key, {})[report['backend']] = sample
    for key, models in matched.items():
        if set(models) == {'native', 'staged'}:
            try:
                verify_semantics(models['native'], models['staged'])
            except RuntimeError as failure:
                print(f'\nPaired evidence for {key[0]}/{key[1]}/{key[2]} documents: NOT_EQUIVALENT_UNDER_EXACT_ORACLE; {failure}. Individual correctness results remain visible; no paired speedup or acceptance is claimed.')
                continue
            print(f'\nPaired semantic evidence matches for {key[0]}/{key[1]}/{key[2]} documents. Model choice still requires the declared service/resource/operational trade-off.')


def percentile(values, fraction):
    return sorted(values)[max(0, math.ceil(len(values) * fraction) - 1)] if values else None


def control_samples(folder, sample):
    raw = folder / str(sample['index']) / 'controls.jsonl'
    values = []
    if raw.exists():
        with raw.open() as stream:
            for line in stream:
                row = json.loads(line)
                if row.get('kind') == 'control' and row.get('eligibleToCompleteNanos') is not None:
                    values.append(row['eligibleToCompleteNanos'])
    return values


def verify_semantics(before, after):
    """Cost ratios require identical independent consumer evidence, not just a green status."""
    if before['input']['sha256'] != after['input']['sha256'] or before['canonical_oracle'] != after['canonical_oracle']:
        raise RuntimeError('Comparison input or independent fields/keys/provenance mismatch')
    if before['canonical_evidence']['deterministic_fingerprints'] != after['canonical_evidence']['deterministic_fingerprints']:
        raise RuntimeError('Comparison identities, receipts or provenance mismatch')
    for field in ('payloadSha256', 'rowsPerScan', 'receiptCount'):
        if before['qualification']['receipt_read_after'][field] != after['qualification']['receipt_read_after'][field]:
            raise RuntimeError('Comparison complete receipt replay mismatch: ' + field)


def compared_metrics(samples):
    print('\n| Point | Profile / inputs | Compared with point 1 | Local time change % | Allocations change % | Receipt scan change % |')
    print('|' + '---|' * 6)
    for key, current in sorted(samples.items(), key=lambda item: (item[0][0], item[0][1], item[0][2] or '', item[0][3] or 0, item[0][4] or 0)):
        point, payloads, profile, count, envelope = key
        if point < 2 or payloads != 'versioned':
            continue
        before = samples.get((1, 'versioned', profile, count, envelope))
        if before is None or any(s['status'] != 'CORRECTNESS_PASS' for s in (before, current)):
            print(f'| {point} | {profile} / {count} | INCOMPLETE | — | — | — |')
            continue
        verify_semantics(before, current)
        def change(old, new):
            return number((new / old - 1) * 100) if old else '—'
        print('| ' + ' | '.join([str(point), f'{profile} / {count}', 'Semantics match; same envelope',
            change(before['local_window_upper_seconds'], current['local_window_upper_seconds']),
            change(before['qualification']['allocatedProcessBytes'], current['qualification']['allocatedProcessBytes']),
            change(before['qualification']['receipt_read_after']['samplesNanos'][0], current['qualification']['receipt_read_after']['samplesNanos'][0])]) + ' |')
    print('\nNegative changes mean lower observed cost. Single-fork differences remain diagnostic and are not causal effect sizes or accepted resource budgets.')


def lifecycle_tables(cells):
    print('\n| Point | Profile / inputs | Canonical DB MiB | Canonical WAL MiB | Bulk workspace peak MiB | Typed rows before / after cleanup | Journal chunks before / after | Extra cleanup s | Receipt allocation MiB |')
    print('|' + '---|' * 9)
    for folder, label, sample, q, peak in cells:
        before, after = q.get('physical_after', {}), q.get('physical_after_cleanup', {})
        storage = q.get('storage_after_cleanup', {})
        pages = q.get('bounded_cleanup', [])
        cleanup = sum(p['eligibleToCompleteNanos'] for p in pages) if pages else None
        reads = q.get('receipt_read_after', {})
        print('| ' + ' | '.join([label, f"{sample.get('profile')} / {sample.get('rows', '—')}",
            number(storage.get('dataframe_db_bytes'), 2**20), number(storage.get('canonical_wal_bytes'), 2**20),
            number(peak.get('workspace_bytes'), 2**20), f"{before.get('typed_version_rows', '—')} / {after.get('typed_version_rows', '—')}",
            f"{before.get('journal_chunk_rows', '—')} / {after.get('journal_chunk_rows', '—')}", number(cleanup, 1e9),
            number(reads.get('allocatedThreadBytes', [None])[0], 2**20)]) + ' |')
    print('\n| Point | Profile / inputs | Control probes | Eligible control p95 / p99 ms | Scheduled lateness max s | Projection metadata p95 / p99 ms | Eligible debt before / after extra pages | Protected rows after |')
    print('|' + '---|' * 8)
    for folder, label, sample, q, peak in cells:
        controls = control_samples(folder, sample)
        reads = q.get('reads_after_publication', {})
        debt_before, debt_after = q.get('cleanup_debt_before_extra_pages', {}), q.get('cleanup_debt_after', {})
        # The census is a post-workload snapshot; no arrival or sustainable reclamation rate is inferred.
        def total(debt, key):
            families = debt.get('families')
            return sum(f[key] for f in families.values() if key in f) if families is not None else '—'
        print('| ' + ' | '.join([label, f"{sample.get('profile')} / {sample.get('rows', '—')}", str(len(controls)),
            number(percentile(controls, .95), 1e6) + ' / ' + number(percentile(controls, .99), 1e6),
            number(q.get('maximum_control_scheduled_lateness_seconds')), number(reads.get('p95_ns'), 1e6) + ' / ' + number(reads.get('p99_ns'), 1e6),
            f"{total(debt_before, 'eligibleRows')} / {total(debt_after, 'eligibleRows')}", str(total(debt_after, 'protectedRows'))]) + ' |')
    print('\nProjection samples read committed metadata, not all canonical rows. Controls and maintenance share the historical serial probe worker; scheduled lateness is retained, and these samples do not establish independent concurrent control load. Cleanup census distinguishes protected rows; a finite drain is not sustainable throughput.')
    print('\n| Point | Profile / inputs | Gate wait / hold s | Native preparation s | Hydration s | Descriptor s | Transfer s | Commit s | Rebuilds |')
    print('|' + '---|' * 9)
    for folder, label, sample, q, peak in cells:
        phases = q.get('publicationPhases', {})
        def duration(name):
            return number(phases.get('timing.' + name + '.totalNanos'), 1e9)
        print('| ' + ' | '.join([label, f"{sample.get('profile')} / {sample.get('rows', '—')}",
            duration('CONTROL_GATE_WAIT') + ' / ' + duration('CONTROL_GATE_HOLD'), duration('NATIVE_PREPARATION'),
            duration('HYDRATION'), duration('DESCRIPTION'), duration('TRANSFER'), duration('COMMIT'), str(phases.get('REBUILD', 0))]) + ' |')
    print('\nPhase totals cover workload plus concurrent controls, overlap by nesting, and must not be summed. Transfer includes admitted transactions/commit; gate hold includes preparation. Post-publication diagnostic work is outside these bulk counters.')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, default=Path('.dev/hybrid-eight'))
    parser.add_argument('--reference', action='store_true', help='Terminal native/hybrid comparison on reference-8g')
    args = parser.parse_args()
    if args.reference:
        reference_tables(args.root)
        return
    print('| Point | Profile | Inputs | Qualification | Local s | Inputs/s | Allocated GiB | Bulk heap MiB | Bulk RSS MiB | Max writer hold ms | Max control wait ms | Receipt scan s |')
    print('|' + '---|' * 12)
    samples = {}
    cells = []
    for folder in sorted(args.root.glob('point-*-measured')) + sorted(args.root.glob('point-*-expanded')) + sorted(args.root.glob('point-8-expanded-append')):
        report = folder / 'report.json'
        if not report.exists():
            continue
        value = json.loads(report.read_text())
        for sample in value['samples']:
            q = sample.get('qualification', sample.get('qualification_at_failure', {}))
            operations = q.get('writerOperations', {})
            hold = max((v.get('maximumHoldNanos', 0) for v in operations.values()), default=None)
            wait = operations.get('CONTROL', {}).get('maximumWaitNanos')
            elapsed = sample.get('local_window_upper_seconds')
            peak = bulk_peaks(folder, sample)
            reads = q.get('receipt_read_after', {})
            scan = reads.get('samplesNanos', [None])[0]
            inputs = sample.get('rows', sample.get('input', {}).get('rows'))
            label = str(value['point']) + (' expanded' if value.get('diagnostic_workspace_envelope', {}).get('workspace_bytes') else ' original') + (' append' if value.get('payloads') == 'append' else '')
            print('| ' + ' | '.join([label, sample.get('profile', 'pending'), str(inputs or '—'), sample['status'],
                number(elapsed), number(inputs / elapsed if inputs and elapsed else None), number(q.get('allocatedProcessBytes'), 2**30),
                number(peak.get('heap_used_bytes'), 2**20), number(peak.get('rss_bytes'), 2**20), number(hold, 1e6), number(wait, 1e6), number(scan, 1e9)]) + ' |')
            samples[(value['point'], value.get('payloads','versioned'), sample.get('profile'), inputs, value.get('diagnostic_workspace_envelope', {}).get('workspace_bytes'))] = sample
            cells.append((folder, label, sample, q, peak))
    # Compare the final receipt layouts only when both complete semantic oracles passed.
    for key, append in samples.items():
        if key[0] != 8 or key[1] != 'append':
            continue
        versioned = samples.get((8, 'versioned', key[2], key[3], key[4]))
        if versioned is None:
            continue
        if any(s['status'] != 'CORRECTNESS_PASS' for s in (versioned, append)):
            print(f'\nFinal receipt comparison {key[2]}/{key[3]}: INCOMPLETE; no success ratio.')
            continue
        verify_semantics(versioned, append)
        print(f'\nFinal receipt comparison {key[2]}/{key[3]}: independent inputs, fields, keys, identities, receipt order and provenance match.')
    compared_metrics(samples)
    lifecycle_tables(cells)
    print('\nBulk heap/RSS are sampled maxima filtered to handoff → local completion. Allocations include concurrent controls and writer drain; post-publication scans/cleanup are excluded from their exact counter. A failed window is not accepted capacity. One fork per cell gives no production percentile or SLO.')


if __name__ == '__main__':
    main()
