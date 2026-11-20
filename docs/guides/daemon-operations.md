# Daemon operations

[Русская версия](ru/daemon-operations.md)

This runbook covers routine operation of an installed ioc-extractor daemon:
submitting documents, checking health, handling failed or blocked work, and
protecting durable state. [Ingestion](../dev/ingestion.md) owns the internal
flow; [deployment](deployment.md) owns installation and upgrade procedures.
Managed CSV inboxes follow the separate [import guide](dataframe-import.md).

## Service lifecycle

```bash
sudo systemctl start ioc-extractor
sudo systemctl stop ioc-extractor
sudo systemctl restart ioc-extractor
sudo systemctl status ioc-extractor --no-pager
sudo journalctl -u ioc-extractor -f
```

Use the installed launcher for application commands:

```bash
sudo /opt/ioc-extractor/bin/ioc --version
sudo /opt/ioc-extractor/bin/ioc health
sudo /opt/ioc-extractor/bin/ioc health --json
```

Examples use the installer's default prefix `/opt/ioc-extractor` and service
account `ioc`. Substitute the selected prefix/account; `deploy-local.sh` defaults
to `/srv/ioc-extractor`. The launcher requires root and runs the command as the
service account with its configured environment. `health` queries the running
daemon; it does not start another daemon or recover its queue. Never run a second
daemon against the same inbox/database namespace.

### Planned restart safety

Startup recovery finishes before ordinary intake opens. A routine restart can
recover owned work, but it does not guarantee an idle configuration/backup point.
For that point:

1. Pause local and remote producers, including scheduled deliveries to import
   inboxes when enabled. Account for remote fetches already in flight; pausing an
   upstream writer alone does not stop the daemon's fetch schedule.
2. Let accepted work drain. Check `var/inbox` and `var/processing`, including
   temporary files, plus durable pending/blocked admissions and any import or
   publication backlog. Empty directories alone do not prove that all work ended.
3. Review local health and recent `INGEST.*` diagnostics. A blocked oldest
   admission requires investigation; a restart is not a queue-clear operation.
4. Stop the service and confirm it has stopped before changing recovery state or
   taking a filesystem backup. For an ordinary restart without a backup/change,
   use the restart command above.
5. Start the service, confirm recovery and local readiness, then resume producers.

The unit allows 60 seconds to stop; dispatcher workers also have bounded shutdown.
A timeout or forced termination is not a clean drain. Preserve the recovery files
and inspect the next startup. If no idle window can be established, use a reviewed
maintenance procedure rather than deleting ledger records.

## Submit a source document

The daemon processes whole files, not an appended byte stream:

```text
inbox -> stability wait -> durable order -> atomic claim -> private sealed source
      -> bounded preparation -> ordered promotion -> canonical commit
      -> CSV projection -> done
verified source rejection -> failed
pre-hash retry exhaustion -> blocked admission, owned token retained
pre-reservation failure -> may remain in inbox (ING-13)
```

Default accepted suffixes are `.htm`, `.html` and `.docx`; `.tmp`, `.part` and
hidden names are excluded. Check `ioc.ingestion.patterns` if configuration changes.
Use a unique delivery filename and an excluded temporary suffix, then rename on
the inbox filesystem after closing the producer's file:

```bash
sudo -u ioc cp report.docx /opt/ioc-extractor/var/inbox/report.docx.part
sudo -u ioc mv /opt/ioc-extractor/var/inbox/report.docx.part \
  /opt/ioc-extractor/var/inbox/report.docx
```

The destination must be unused; do not overwrite an earlier delivery or let two
producers share this temporary name. The default quiet period is 10 seconds and
full-listing interval 30 seconds. These are observations, not a producer completion
protocol: a paused copy into its final name can look stable too early.

Keep full polling (`ioc.ingestion.detect.use-watch-service=false`) for network or
unreliable filesystems. Opt-in WatchService has no periodic full-listing backstop;
it can miss an unadmitted file after event loss. The durable dispatcher scan only
recovers work already admitted.

Do not write directly to `processing`, `done` or `failed`, or modify an owned
sealed file. Archive names contain ownership/hash metadata and can end in
`.sealed`; do not depend on the original filename suffix. Do not edit generated
`dataframe/` files; they are projections of canonical storage.

## Understand outcomes

A source archived under `var/done` completed its source lifecycle. New canonical
rows have non-reusable internal identities; duplicate row identity keeps the
existing row while accepted observations can update lifecycle/provenance under
the configured policy. Another document does not reset the dataset. Immutable
export `id` is a separate reusable slot; see the [lifecycle guide](canonical-record-lifecycle.md).

With `collect-and-continue`, valid rows may commit despite item diagnostics.
`COMPLETED_WITH_ERRORS` therefore needs review even when the source reaches
`done`. Document writes commit per artifact: a later artifact/projection failure
does not roll back earlier canonical writes. Health is not a per-source commit
report. The partial-run resume limitation is tracked as `ING-11` in
[known issues](../KNOWN-ISSUES.md).

| Situation | Where to look / action |
|---|---|
| Verified source rejected after bounded attempts | `var/failed`, its `.error` sidecar and diagnostics; retain the source for investigation |
| Failure before a trustworthy content key, attempts exhausted | Owned token/admission can remain in `processing`; `dataProcessingCapacity` is `DOWN` and later promotions wait behind the blocked head |
| Unclaimed oversized input or admission count/byte saturation | File remains in `inbox`; full polling retries admission after pressure changes |
| Pre-reservation failure | May remain in `inbox` (`ING-13`); preserve logs and file |
| Interrupted claim, promotion or projection | Keep journals and owned files intact for startup recovery |

There is no supported document queue clear/requeue command. Moving a terminal
source back to the inbox or deleting ledger/SQLite records is not a supported
retry; identical content can remain terminally deduplicated. Fix the cause and
use a reviewed recovery or coordinated restore. Do not use the managed-import
`replay` command for document ingestion.

## Health checks

`ioc health` queries actuator and returns a table; `--json` returns the response
body. Exit codes apply to the queried aggregate or selected component:

| Exit | Meaning |
|---|---|
| `0` | Parsed `status` is exactly `UP` |
| `1` | Parsed status is anything else, including `DEGRADED`, `UNKNOWN` or `DOWN` |
| `2` | Endpoint unreachable, request failed or response could not be parsed |

```bash
sudo /opt/ioc-extractor/bin/ioc health --component ingestionLifecycle
sudo /opt/ioc-extractor/bin/ioc health --component dataProcessingCapacity --json
sudo /opt/ioc-extractor/bin/ioc health --component sync
curl --noproxy '*' --silent --show-error http://127.0.0.1:8081/actuator/health
```

Use the configured actuator port when it differs. The curl command keeps error
response bodies for diagnosis; its exit code alone does not validate JSON health.
`ingestionLifecycle` is `UP` only after recovery and running intake.
`dataProcessingCapacity` is `DOWN` if dispatch stopped or an admission is blocked;
its details expose queue age/bytes, phase counts and writer/workspace pressure.
`UP` does not certify a capacity budget or successful processing of every source.

An optional sync component can be `UNKNOWN` before its first observation or
`DEGRADED` during recovery/backlog. Inspect individual components instead of
interpreting aggregate status as proof of local readiness. The deployment gate
checks local dependencies separately from optional remote availability.
When managed import is enabled, its status/health read can fail for a pending
head with no retry deadline (`OPS-10` in [known issues](../KNOWN-ISSUES.md)); this
is an observation failure, not proof that the delivery rolled back or vanished.

## Logs and diagnostics

With the shipped `daemon` profile, structured ECS JSON is written to
`var/logs/ioc-extractor.ecs.json` and rotated there. Console output is also
available through the systemd journal; it need not have the same JSON format.

```bash
sudo journalctl -u ioc-extractor --since "30 minutes ago" --no-pager
```

Correlate filename, observation/source identity, run id and time; a private token
need not retain the exact producer name. Use diagnostic codes (`CONFIG.*`,
`SOURCE.*`, `SINK.*`, `INGEST.*`, `EXPORT.*`, `SYNC.*`) and the application's
severity fields. Journal priority filtering alone can miss Java warnings written
to the console stream. INFO/WARN intentionally avoid raw IOC and credentials.
Enable per-item TRACE only for a controlled diagnostic window.

For startup failures, read every `CONFIG.*` action, inspect value-free winning
key reports, and compare YAML with the [configuration guide](configuration.md).
Fix every invalid/unknown key before restarting. An ingestion recovery failure
keeps intake closed; do not bypass the barrier to resume polling.

## Failed or blocked source triage

1. Pause automated resubmission. Record filename, time, build version,
   observation/run identity when available, and diagnostic codes.
2. Preserve the source where it was left (`failed`, `inbox` or an owned token in
   `processing`), sidecars and journals; treat them as potentially sensitive.
3. Check format/content, permissions, available disk, storage and resource limits.
   For a blocked pre-hash head, preserve its original admission order.
4. Review run/receipt and projection evidence before deciding what committed.
   A failed source or healthy aggregate is insufficient on its own.
5. Correct the cause and follow a reviewed recovery procedure without deleting
   durable state.

Normal startup recovers recognized claims and incomplete committed runs.
Unrecognized orphan files may be isolated as failures; they must not be renamed
into success by hand. Recovery preserves authority and order, rather than
inventing a new admission rank for unresolved legacy work.

## Backlog and capacity

Monitor pending count, source bytes, oldest age and blocked count in
`dataProcessingCapacity`, as well as inbox age, free space, DB/WAL size, workspace
pressure, and export/publish lag. Phase/completion counters complement directory
inspection; some are process-local and reset on restart.

`ioc.ingestion.concurrency` must stay `1`. This serializes detection/promotion,
while `ioc.ingestion.execution.preparation-workers` permits up to two parallel
preparations. The default window is four with 64 pending documents, 4 GiB pending
source bytes and 512 MiB per source. These are separate budgets from workspace
memory/disk and do not promise a whole-process memory ceiling. Raising a limit
requires capacity evidence, not just successful startup. Diagnose a blocked head,
slow storage/parser, copies or remote outage before tuning the queue.

## Retention

Coordinate the policies; they have different owners:

- `ioc.maintenance.retention` cleans configured leaf directories such as `done`
  and `failed`; it is disabled in the shipped defaults until investigation and
  backup windows are agreed.
- `ioc.export.retention` cleans old unpinned immutable slices; pending delivery
  can pin them. See [remote storage](remote-storage-sync.md).
- Lifecycle history/receipt cleanup and managed-import terminal cleanup have
  their own recovery obligations; see the [lifecycle](canonical-record-lifecycle.md)
  and [import](dataframe-import.md) guides. Log rotation is separate again.

Never use leaf-file retention on database/export roots, inbox, processing,
admission journals or preparation workspaces. Do not delete pinned files merely
to relieve disk pressure; restore capacity or use the owner's cleanup procedure.

## Backup and recovery

A coordinated stopped-service backup needs both canonical and service databases,
file-backed journals (default `var/ledger`), owned sources/admissions and any
referenced workspace/snapshot/export files, plus the matching binary and
configuration. Inventory all configured paths, including those outside the prefix.
A DB-only copy can leave recoverable claims without their source or receipt state.

Never copy a live SQLite main file alone or discard its WAL. Stop all writers
before a filesystem snapshot and keep database sidecars with the consistent set;
use a separately qualified SQLite backup procedure for an online backup.
Generated mutable CSV projections can be rebuilt, whereas pinned immutable slices
and recovery inputs must survive with their ledgers. Do not mix database files
or journals from different backup instants.

Follow the [deployment guide](deployment.md) for upgrade/rollback compatibility;
a copied DB directory alone is not a complete rollback point. Test the coordinated
restore on a separate host before treating it as verified recovery.

## Routine checklist

- Service, recovery barrier and required local components are ready.
- Inbox age, admitted backlog and blocked count are reviewed.
- Failed files and partial outcomes have a disposition.
- Disk covers databases/WAL, workspaces, slices, logs and backup needs.
- Retention preserves recovery pins and investigation evidence.
- Build/configuration identity is recorded after deployment.
- Optional remote sync and enabled import are checked using their own guides.
