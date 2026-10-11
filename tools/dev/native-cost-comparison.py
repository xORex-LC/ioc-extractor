#!/usr/bin/env python3
"""Compare completed fixed native-cost series; semantic equality never accepts a resource budget."""
import argparse
import hashlib
import json
from pathlib import Path
import statistics


def distribution(values):
    return {'samples': values, 'median': statistics.median(values), 'range': [min(values), max(values)]}


def compare(before, after):
    required = ('source_commit', 'jar_sha256', 'rows', 'format', 'shape', 'policy_sha256',
                'driver_sha256', 'service_driver_sha256', 'helper_class_sha256', 'java')
    for series in (before, after):
        if any(not series.get(field) for field in required) or not series.get('samples'):
            raise ValueError('Missing measurement identity or samples')
    common = ('rows', 'format', 'shape', 'initial_rows', 'import_artifact', 'processed_import',
              'policy_sha256', 'common_effective_policy_sha256', 'bounded_row_profile',
              'extra_maintenance_every_control_probes', 'java', 'mode', 'native_cost_cycle',
              'driver_sha256', 'service_driver_sha256', 'helper_class_sha256')
    for field in common:
        if before.get(field) != after.get(field):
            raise ValueError('Measurement contract differs: ' + field)
    if before.get('mode') != 'instrumented_primary' or before.get('native_cost_cycle') is not True:
        raise ValueError('Only native primary cost series are comparable')
    reference = before['samples'][0]
    for series in (before, after):
        if (series.get('status') != 'COMPLETE' or series.get('cost_series', {}).get('status') != 'COMPLETE'
                or len(series['samples']) != series.get('requested_native_windows')):
            raise ValueError('Incomplete cost series')
        for sample in series['samples']:
            if (sample.get('backend') != 'native' or sample.get('status') not in ('SCREEN_PASS', 'FAIL')
                    or sample.get('failure') or sample.get('cleanup_errors')
                    or not sample.get('temporary_state_removed')
                    or not sample.get('qualification', {}).get('admissionWindowMaximaExact')):
                raise ValueError('Incomplete native measurement window')
            if (sample['input']['sha256'] != reference['input']['sha256']
                    or sample['canonical_oracle'] != reference['canonical_oracle']
                    or not sample['canonical_evidence'].get('deterministic_fingerprints')
                    or sample['canonical_evidence']['deterministic_fingerprints']
                    != reference['canonical_evidence']['deterministic_fingerprints']):
                raise ValueError('Physical input or canonical fields/keys/IDs/provenance/receipt differ')
            for field in ('effective_limits', 'flags'):
                if sample['runtime'][field] != reference['runtime'][field]:
                    raise ValueError('Runtime resources differ: ' + field)
    metrics = {
        'local_window_upper_seconds': lambda s: s['local_window_upper_seconds'],
        'physical_inputs_per_second': lambda s: before['rows'] / s['local_window_upper_seconds'],
        'allocated_process_bytes': lambda s: s['qualification']['allocatedProcessBytes'],
        'cpu_seconds': lambda s: s['resources']['process_cpu_seconds'],
        'heap_peak_bytes': lambda s: s['resources']['peaks']['heap_used_bytes'],
        'rss_peak_bytes': lambda s: s['resources']['peaks']['rss_bytes'],
        'non_file_peak_bytes': lambda s: s['resources']['peaks']['non_file_bytes'],
        'workspace_peak_bytes': lambda s: s['resources']['peaks']['workspace_bytes'],
        'canonical_wal_peak_bytes': lambda s: s['resources']['peaks']['canonical_wal_bytes'],
        'promotion_max_hold_seconds': lambda s: s['qualification']['writerOperations']['PROMOTION']['maximumHoldNanos'] / 1e9,
        'control_max_wait_seconds': lambda s: s['qualification']['writerOperations']['CONTROL']['maximumWaitNanos'] / 1e9,
    }
    compared = {}
    for name, accessor in metrics.items():
        left = distribution([accessor(s) for s in before['samples']])
        right = distribution([accessor(s) for s in after['samples']])
        compared[name] = {'before': left, 'after': right,
                          'after_before_median_ratio': right['median'] / left['median'] if left['median'] else None}
    return {'semantics': 'PASS; equal physical inputs, independent final fields/keys/provenance and deterministic identity/receipt fingerprints',
            'acceptance': 'NOT_ACCEPTED; no new resource budget, C5/G6 closure or staged activation',
            'comparison_scope': 'Sequential fixed fork cohorts, conservative full-service windows; finite n, no p95 claim',
            'input_sha256': reference['input']['sha256'],
            'canonical_oracle': reference['canonical_oracle'],
            'deterministic_fingerprints': reference['canonical_evidence']['deterministic_fingerprints'],
            'effective_limits': reference['runtime']['effective_limits'], 'flags': reference['runtime']['flags'],
            'identities': {'before': {k: before.get(k) for k in ('source_commit', 'jar_sha256', *common)},
                           'after': {k: after.get(k) for k in ('source_commit', 'jar_sha256', *common)}},
            'screen_statuses': {'before': [s['status'] for s in before['samples']],
                                'after': [s['status'] for s in after['samples']]},
            'screen_violations': {'before': [s['gates']['violations'] for s in before['samples']],
                                  'after': [s['gates']['violations'] for s in after['samples']]},
            'metrics': compared}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--before', type=Path, required=True)
    parser.add_argument('--after', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    inputs = {'before': args.before.read_bytes(), 'after': args.after.read_bytes()}
    result = compare(json.loads(inputs['before']), json.loads(inputs['after']))
    result['raw_report_sha256'] = {side: hashlib.sha256(content).hexdigest() for side, content in inputs.items()}
    result['comparison_driver_sha256'] = hashlib.sha256(Path(__file__).read_bytes()).hexdigest()
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n')


if __name__ == '__main__':
    main()
