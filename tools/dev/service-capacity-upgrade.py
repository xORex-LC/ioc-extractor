#!/usr/bin/env python3
"""Private stopped-state upgrade/restore rehearsal, never an old binary over a new schema."""
import argparse
from contextlib import closing
import hashlib
import importlib.util
import json
from pathlib import Path
import shutil
import sqlite3
import sys
import tempfile
import time

sys.dont_write_bytecode = True
SPEC = importlib.util.spec_from_file_location('cap6', Path(__file__).with_name('service-capacity.py'))
CAP = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CAP)


def state(root, config):
    result = {}
    for name in ('service', 'dataframe'):
        with closing(sqlite3.connect(f'file:{root}/var/db/ioc-{name}.db?mode=ro', uri=True)) as db:
            result[name + '_schema'] = db.execute('PRAGMA user_version').fetchone()[0]
            if name == 'dataframe':
                for artifact in config['ioc']['sink']['artifacts']:
                    table = artifact['name']
                    names = [column['name'] for column in artifact['columns'] if column['name'] != 'id']
                    columns = ['row_key', 'id', '_lifecycle_id', '_valid_until_epoch_ms'] + names
                    count, digest = 0, hashlib.sha256()
                    for row in db.execute(f'SELECT {",".join(columns)} FROM {table} ORDER BY row_key'):
                        digest.update(json.dumps(row, ensure_ascii=False).encode() + b'\n')
                        count += 1
                    origins = hashlib.sha256()
                    for row in db.execute(f'SELECT row_id,source_key,occurrences FROM {table}_sources ORDER BY row_id,source_key'):
                        origins.update(json.dumps(row).encode() + b'\n')
                    result[table] = {'rows': count, 'fields_identity_lifecycle_sha256': digest.hexdigest(),
                                     'provenance_sha256': origins.hexdigest()}
    return result


def await_previous_outputs(unit, root, config, oracle, key):
    deadline = time.monotonic() + 90
    while time.monotonic() < deadline:
        unit.assert_running()
        runs = CAP.BASE.rows(root / 'var/db/ioc-service.db',
                             'SELECT status FROM ingest_run WHERE source_key=?', (key,))
        if any(run['status'] == 'COMPLETED' for run in runs):
            revisions = {r['artifact']: r['revision'] for r in CAP.BASE.rows(root / 'var/db/ioc-dataframe.db', 'SELECT * FROM artifact_revision')}
            found = {}
            for run in CAP.BASE.rows(root / 'var/db/ioc-service.db', 'SELECT * FROM export_run WHERE status=?', ('COMPLETED',)):
                folder = root / 'var/export' / run['profile'] / run['slice_name']
                manifest = json.loads((folder / 'manifest.json').read_text())
                if all(a['coverage']['revision'] == revisions[a['artifact']] for a in manifest['artifacts']):
                    found[run['profile']] = {'run': run, 'manifest': manifest, 'path': str(folder)}
            if set(found) == {p['name'] for p in config['ioc']['export']['profiles']}:
                CAP.verify_slices({'profiles': found}, oracle, root / 'var/db/ioc-dataframe.db')
                oracle.check_database(root / 'var/db/ioc-dataframe.db')
                return
        time.sleep(.1)
    raise RuntimeError('Previous executable did not finish the drained reference')


def snapshot_files(directory):
    """Hash a stopped filesystem snapshot, including journals and immutable outputs."""
    return {str(path.relative_to(directory)): CAP.BASE.digest(path)
            for path in sorted(directory.rglob('*')) if path.is_file()}


def rehearse(args):
    output = args.output.absolute()
    if output.exists() or not output.is_relative_to(CAP.REPO / '.dev') or output.resolve() != output:
        raise ValueError('New owned repo-local .dev output required')
    if CAP.command(['git', '-C', str(CAP.REPO), 'status', '--porcelain']):
        raise ValueError('Commit the harness and policy before rehearsal')
    output.mkdir(parents=True)
    report = {'status': 'RUNNING', 'source_commit': CAP.command(['git', 'rev-parse', 'HEAD']),
              'previous_sha256': CAP.BASE.digest(args.previous), 'candidate_sha256': CAP.BASE.digest(args.candidate),
              'driver_sha256': CAP.BASE.digest(__file__), 'policy_sha256': CAP.BASE.digest(args.config),
              'scope': 'Drained, publicly seeded private state; no in-flight or production rollback claim', 'units': []}
    unit, oracle, root, label = None, None, None, 'setup'
    root = Path(tempfile.mkdtemp(prefix='ioc-cap6-upgrade-', dir=CAP.REPO / '.dev'))
    try:
        frozen = {}
        for label, executable in (('previous', args.previous), ('candidate', args.candidate)):
            path = root / (label + '.jar')
            shutil.copyfile(executable, path)
            if CAP.BASE.digest(path) != report[label + '_sha256']:
                raise RuntimeError('Executable changed during freeze')
            frozen[label] = path
        config = CAP.private_config(args.config, root, 18207)
        # An older executable must receive its own supported configuration shape.
        previous_config = CAP.yaml.safe_load((root / 'application.yml').read_text())
        original = CAP.yaml.safe_load(args.config.read_text())
        if 'workspace' not in original['ioc'].get('processing', {}):
            previous_config['ioc']['processing'].pop('workspace', None)
        (root / 'previous.yml').write_text(CAP.yaml.safe_dump(previous_config, sort_keys=False))
        oracle = CAP.DiskOracle(config, root / 'oracle.db')
        source = root / 'seed.html'
        report['input'] = CAP.fixture(source, 1000)
        CAP.feed_fixture(oracle, source)
        for label, executable in (('previous', frozen['previous']), ('candidate', frozen['candidate']), ('restored_previous', frozen['previous'])):
            if label == 'restored_previous':
                shutil.rmtree(root / 'var')
                shutil.rmtree(root / 'dataframe')
                shutil.copytree(root / 'backup/var', root / 'var')
                shutil.copytree(root / 'backup/dataframe', root / 'dataframe')
                if any(snapshot_files(root / name) != snapshot_files(root / 'backup' / name) for name in ('var', 'dataframe')):
                    raise RuntimeError('Stopped filesystem restore differs before old executable launch')
                restored = state(root, config)
                if restored != report['previous']:
                    raise RuntimeError('Stopped backup restore differs before old executable launch')
                report['restore_verified_before_old_launch'] = True
            unit = CAP.PrivateUnit(root)
            policy = root / ('application.yml' if label == 'candidate' else 'previous.yml')
            launch = unit.start(executable.resolve(), policy)
            CAP.ready(unit, 18207)
            if label == 'previous':
                CAP.local_handoff(source, root)
                await_previous_outputs(unit, root, config, oracle, CAP.BASE.digest(source))
            else:
                await_previous_outputs(unit, root, config, oracle, CAP.BASE.digest(source))
            for artifact in config['ioc']['sink']['artifacts']:
                oracle.check_csv(artifact['name'], root / artifact['path'], root / 'var/db/ioc-dataframe.db', None)
            report[label] = state(root, config)
            if label != 'previous':
                if {k:v for k,v in report[label].items() if not k.endswith('_schema')} != {k:v for k,v in report['previous'].items() if not k.endswith('_schema')}:
                    raise RuntimeError('Canonical fields/IDs/lifecycle/provenance changed across drained upgrade/restore')
            report['units'].append({'label': label, 'runtime': launch, 'cleanup': unit.close()})
            unit = None
            shutil.copy2(root / 'daemon.log', output / (label + '.log'))
            (root / 'daemon.log').unlink()
            if label == 'previous':
                # No writer/reader process is alive while the coherent backup is made.
                shutil.copytree(root / 'var', root / 'backup/var')
                shutil.copytree(root / 'dataframe', root / 'backup/dataframe')
                report['stopped_backup_verified'] = all(snapshot_files(root / name) == snapshot_files(root / 'backup' / name)
                                                        for name in ('var', 'dataframe'))
                if not report['stopped_backup_verified']:
                    raise RuntimeError('Coherent stopped backup copy differs')
        oracle.close()
        oracle = None
        report['status'] = 'PASS'
    except Exception as failure:
        report.update(status='ERROR', failure=str(failure), failure_stage=label)
    finally:
        errors, terminated = [], unit is None
        if unit:
            try:
                report['last_cleanup'] = unit.close()
                terminated = report['last_cleanup']['process_terminated']
            except Exception as failure:
                errors.append('Unit stop: ' + str(failure))
        if oracle:
            try:
                oracle.close()
            except Exception as failure:
                errors.append('Oracle close: ' + str(failure))
        if (root / 'daemon.log').is_file():
            try:
                shutil.copy2(root / 'daemon.log', output / (label + '-failure.log'))
            except Exception as failure:
                errors.append('Evidence copy: ' + str(failure))
        if terminated:
            try:
                shutil.rmtree(root)
            except Exception as failure:
                errors.append('State removal: ' + str(failure))
        else:
            report['state_preserved_until_unit_termination'] = str(root)
        report['cleanup_errors'] = errors
        if errors:
            report['status'] = 'ERROR'
        report['temporary_state_removed'] = root is not None and not root.exists()
        if CAP.command(['git', '-C', str(CAP.REPO), 'rev-parse', 'HEAD']) != report['source_commit'] or CAP.command(['git', '-C', str(CAP.REPO), 'status', '--porcelain']):
            report.update(status='ERROR', failure='HEAD/worktree changed during rehearsal')
        CAP.save(output / 'report.json', report)
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('previous', 'candidate', 'config', 'output'):
        parser.add_argument('--' + name, type=Path, required=True)
    report = rehearse(parser.parse_args())
    print(json.dumps(report, ensure_ascii=False, indent=2))
    raise SystemExit(0 if report['status'] == 'PASS' else 1)


if __name__ == '__main__':
    main()
