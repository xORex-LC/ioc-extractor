#!/usr/bin/env python3
"""Exact-driver bounded chunk transport diagnostic; never substitutes for C5."""
import argparse
import importlib.util
import json
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile

sys.dont_write_bytecode = True
SPEC = importlib.util.spec_from_file_location('sqlite_capacity', Path(__file__).with_name('sqlite-publication-capacity.py'))
C5 = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(C5)
CAP = C5.CAP


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jar', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--chunks', type=int, default=200)
    parser.add_argument('--samples', type=int, default=3)
    args = parser.parse_args()
    output = args.output.absolute()
    if not output.is_relative_to(C5.REPO / '.dev') or output.exists() or output.resolve() != output:
        parser.error('Use a new private .dev directory without symlinks')
    if not 1 <= args.chunks <= 1000 or args.samples < 1:
        parser.error('Positive samples and 1..1000 chunks required')
    if CAP.command(['git', 'status', '--porcelain']):
        parser.error('Commit the probe before measuring')
    head = CAP.command(['git', 'rev-parse', 'HEAD'])
    source = Path(__file__).with_name('JdbcTypedChunkCostProbe.java')
    output.mkdir(parents=True)
    report = dict(source_commit=head, jar_sha256=CAP.BASE.digest(args.jar),
                  probe_sha256=CAP.BASE.digest(source), driver_sha256=CAP.BASE.digest(__file__),
                  java=CAP.command(['java', '--version']), chunks=args.chunks,
                  flags=['-Xms128m', '-Xmx512m', '-XX:ActiveProcessorCount=2'],
                  scope='Isolated sequential typed chunks + journal + commit; exact caller allocations; '
                        'no Spring/hydration/output/mixed load/heap/RSS or C5 acceptance', samples=[])
    CAP.save(output / 'report.json', report)
    try:
        with tempfile.TemporaryDirectory(prefix='ioc-chunk-cost-', dir=C5.REPO / '.dev') as temporary:
            root = Path(temporary)
            frozen = root / 'ioc-app.jar'
            shutil.copyfile(args.jar, frozen)
            cp, _ = C5.freeze_runtime(frozen, root)
            compiler_cp = str(root / 'classes') + ':' + ':'.join(str(path) for path in sorted((root / 'lib').glob('*.jar')))
            CAP.command(['java', '--add-modules', 'jdk.compiler', 'com.sun.tools.javac.Main',
                         '-cp', compiler_cp, '-d', str(root / 'classes'), str(source)])
            report['probe_classes_sha256'] = {path.name: CAP.BASE.digest(path) for path in
                (root / 'classes/com/iocextractor/adapter/out/store/jdbc').glob('JdbcTypedChunkCostProbe*.class')}
            for ordinal in range(args.samples):
                text = CAP.command(['java', *report['flags'], '-cp', cp,
                    'com.iocextractor.adapter.out.store.jdbc.JdbcTypedChunkCostProbe', str(root / f'chunk-{ordinal}.db'), str(args.chunks)],
                    timeout=300)
                (output / f'sample-{ordinal}.log').write_text(text)
                values = [json.loads(line.removeprefix('CHUNK_JSON=')) for line in text.splitlines() if line.startswith('CHUNK_JSON=')]
                if len(values) != 1 or values[0]['oracle'] != 'PASS' or values[0]['rows'] != args.chunks * 256:
                    raise RuntimeError('Missing complete chunk oracle')
                report['samples'].append(values[0])
                CAP.save(output / 'report.json', report)
        if CAP.command(['git', 'rev-parse', 'HEAD']) != head or CAP.command(['git', 'status', '--porcelain']):
            raise RuntimeError('Source changed during diagnostic')
        report.update(status='COMPLETE', temporary_state_removed=not root.exists())
    except Exception as failure:
        report.update(status='ERROR', failure=str(failure))
        raise
    finally:
        CAP.save(output / 'report.json', report)


if __name__ == '__main__':
    main()
