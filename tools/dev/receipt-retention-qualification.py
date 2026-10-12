#!/usr/bin/env python3
"""Finite parent-retirement/physical-cleanup qualification, separate from whole-service timing."""
import argparse
import csv
import importlib.util
import io
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET

sys.dont_write_bytecode = True
SPEC = importlib.util.spec_from_file_location('journal_comparison', Path(__file__).with_name('typed-journal-comparison.py'))
JOURNAL = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(JOURNAL)
CAP, ACK = JOURNAL.CAP, JOURNAL.ACK


def classpath_entries(value, output):
    """Exclude Failsafe's implicit cwd and reject recursive directory freezes."""
    paths = [part for part in value.split(':') if part]
    for value in paths:
        source = Path(value).resolve()
        if source.is_dir() and (source == JOURNAL.C5.REPO or output.is_relative_to(source)):
            raise ValueError('Classpath directory contains the output or repository root')
    return paths


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--rows', type=int, default=2000)
    parser.add_argument('--cycles', type=int, default=3)
    parser.add_argument('--classpath-file', type=Path,
                        help='Explicit local diagnostic classpath when reactor gates are deferred; no gate freshness claim')
    parser.add_argument('--test-report', type=Path, default=JOURNAL.C5.REPO / 'adapters/adapter-store-jdbc/target/failsafe-reports/'
                        'TEST-com.iocextractor.adapter.out.store.jdbc.ImmutableReceiptPayloadIT.xml')
    args = parser.parse_args()
    output = args.output.absolute()
    if not output.is_relative_to(JOURNAL.C5.REPO / '.dev') or output.exists() or output.resolve() != output:
        parser.error('Use a new private .dev directory without symlinks')
    if not 1 <= args.rows <= 10000 or not 1 <= args.cycles <= 5:
        parser.error('Use 1..10000 rows and 1..5 cycles')
    if args.classpath_file:
        paths = classpath_entries(args.classpath_file.read_text().strip(), output)
        precondition = 'Explicit compiled diagnostic classpath; reactor quality gates deferred by owner'
    else:
        suite = ET.parse(args.test_report).getroot()
        if int(suite.get('errors', '0')) or int(suite.get('failures', '0')):
            parser.error('A successful focused receipt suite is required')
        properties = {p.get('name'): p.get('value') for p in suite.findall('properties/property')}
        # Failsafe appends an empty classpath component; never copy the implicit current directory.
        paths = classpath_entries(properties['surefire.test.class.path'], output)
        precondition = 'Successful focused Failsafe receipt suite; freshness not inferred'
    identity = ACK.source_identity()
    output.mkdir(parents=True)
    report = dict(source=identity, status='RUNNING', rows=args.rows, cycles=args.cycles, samples=[], precondition=precondition,
                  java=CAP.command(['java', '--version']), flags=['-Xms128m', '-Xmx512m', '-XX:ActiveProcessorCount=2'],
                  scope='File-backed real adapter ports; finite interleaved publication/retirement/cleanup and pinned reads; '
                        'not Spring throughput, scheduled retention or sustained-rate acceptance', resource_acceptance='OPEN')
    try:
        with tempfile.TemporaryDirectory(prefix='ioc-receipt-retention-', dir=JOURNAL.C5.REPO / '.dev') as directory:
            root = Path(directory)
            frozen = []
            for index, value in enumerate(paths):
                source = Path(value)
                target = root / ('cp-' + str(index) + ('.jar' if source.is_file() else ''))
                if source.is_dir(): shutil.copytree(source, target)
                else: shutil.copyfile(source, target)
                frozen.append(str(target))
            report['frozen_sha256'] = {str(p.relative_to(root)): CAP.BASE.digest(p) for p in sorted(root.rglob('*')) if p.is_file()}
            for mode in ('VERSIONED', 'IMMUTABLE'):
                state = root / mode
                state.mkdir()
                run = subprocess.run(['java', *report['flags'], '-cp', ':'.join(frozen),
                         'com.iocextractor.adapter.out.store.jdbc.ReceiptPayloadQualification', str(state), mode,
                         str(args.rows), str(args.cycles)], stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, timeout=300)
                (output / (mode.lower() + '.log')).write_text(run.stdout)
                if run.returncode: raise RuntimeError('Receipt retention probe failed: ' + mode)
                lines = [line for line in run.stdout.splitlines() if line.startswith('cycle,') or line[:1].isdigit()]
                rows = list(csv.DictReader(io.StringIO('\n'.join(lines))))
                catchup = [line for line in run.stdout.splitlines() if line.startswith('CATCHUP_NS=')]
                if len(rows) != args.cycles or len(catchup) != 1 or 'PAYLOAD_DEBT=0' not in catchup[0]:
                    raise RuntimeError('Incomplete retention/debt oracle: ' + mode)
                report['samples'].append(dict(layout=mode, cycles=rows, catchup=catchup[0], oracle='PASS'))
                CAP.save(output / 'report.json', report)
        if ACK.source_identity() != identity: raise RuntimeError('Source changed during frozen qualification')
        report.update(status='COMPLETE', temporary_state_removed=not root.exists())
    except BaseException as failure:
        report.update(status='ERROR', failure=str(failure))
        raise
    finally:
        CAP.save(output / 'report.json', report)


if __name__ == '__main__':
    main()
