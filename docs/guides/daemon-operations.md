# Daemon operations

This runbook covers routine operation of an installed ioc-extractor daemon:
submitting sources, checking health, reading logs, handling failed files and
protecting durable state.

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
sudo -u ioc /opt/ioc-extractor/bin/ioc --version
sudo -u ioc /opt/ioc-extractor/bin/ioc health
sudo -u ioc /opt/ioc-extractor/bin/ioc health --json
```

The default prefix is `/opt/ioc-extractor`; substitute the selected installation
prefix when different.

## Submit a source document

The daemon processes whole files, not an appended byte stream. Its lifecycle is:

```text
inbox → stability wait → atomic claim into processing
      → canonical DB commit → CSV projection → done
      └ terminal failure                         → failed
```

For a local file, copy with a temporary excluded suffix and rename when complete:

```bash
sudo -u ioc cp report.docx /opt/ioc-extractor/var/inbox/report.docx.part
sudo -u ioc mv /opt/ioc-extractor/var/inbox/report.docx.part \
  /opt/ioc-extractor/var/inbox/report.docx
```

The rename avoids waiting for a long copy and makes producer completion explicit.
Direct copying into the final name is also protected by the configured stability
quiet period, but the producer must not keep modifying the file indefinitely.

Do not write directly to `processing`, `done` or `failed`. Do not edit generated
files under `dataframe/`; they are projections rebuilt from canonical storage.

## Understand outcomes

A successfully processed source is archived under `var/done`. New canonical rows
receive stable public IDs; repeated IOC rows are retained once while source
provenance accumulates. Reprocessing another document therefore does not reset or
replace the dataset.

Recoverable item-level errors under `collect-and-continue` can produce a completed
run with diagnostics. Valid rows may still be committed, and logs/health must be
reviewed even when the source reaches `done`.

After bounded retries, a terminal source failure is moved to `var/failed` and a
durable terminal ledger state is recorded. Version 0.1.1 has no supported command
that clears or requeues that terminal identity. Do not edit ledger files or SQLite
tables manually. Preserve the failed source and logs, correct the cause, and use a
reviewed recovery procedure; identical content may remain terminally deduplicated.

## Health checks

`ioc health` queries the loopback actuator and returns a table. Its process exit
codes are suitable for scripts:

| Exit | Meaning |
|---|---|
| `0` | Overall status is healthy, including accepted degraded states. |
| `1` | A component is down or the health response is unhealthy. |
| `2` | The endpoint could not be queried or the response could not be interpreted. |

Useful commands:

```bash
ioc health
ioc health --component sync
ioc health --json
curl --fail --silent http://127.0.0.1:8081/actuator/health
```

Interpret common statuses as follows:

| Status | Operator interpretation |
|---|---|
| `UP` | Component has a successful current observation. |
| `DEGRADED` | Service remains available, but backlog, recovery or optional integration needs attention. |
| `UNKNOWN` | No conclusive operation has occurred yet, commonly immediately after startup for optional sync. |
| `DOWN` | Required local dependency or observed operation failed. Investigate before submitting more work. |

The deployment health gate intentionally validates local storage and application
readiness, not availability of every optional SMB endpoint.

## Logs and diagnostics

Daemon logs are ECS JSON files under `var/logs` and are also available through
the systemd journal. Start with journal events around startup or the source
filename:

```bash
sudo journalctl -u ioc-extractor --since "30 minutes ago" --no-pager
sudo journalctl -u ioc-extractor -p warning --since today --no-pager
```

Stable diagnostic codes such as `CONFIG.*`, `SOURCE.*`, `SINK.*`, `INGEST.*`,
`EXPORT.*` and `SYNC.*` identify the failure area. INFO/WARN logs intentionally
avoid raw IOC values and credentials. Enable per-item TRACE only for a short,
controlled diagnostic window and disable it afterwards.

For startup failures:

1. read the complete `CONFIG.*` action list;
2. inspect value-free override reporting to see which external keys won;
3. compare the operator YAML with the [configuration reference](configuration.md);
4. correct every unknown/invalid key before restarting.

## Failed-source triage

1. Stop automated resubmission by the producer.
2. Record the source filename, timestamp, build version and relevant diagnostic
   codes.
3. Preserve the source in `var/failed`; treat it as potentially sensitive.
4. Determine whether the cause is input format/content, configuration, storage,
   permissions or resource exhaustion.
5. Check canonical and projection health before deciding whether any data was
   committed.
6. Correct the cause and follow an approved recovery procedure. Do not delete
   durable ledger state simply to make the file run again.

An orphan discovered in `processing` is isolated as a failure rather than silently
treated as successful. On normal restart, durable recovery replays incomplete
claimed work and completes a committed DB run by rebuilding projections.

## Backlog and capacity

Monitor:

- file counts and oldest age in `inbox`, `processing`, `done` and `failed`;
- free space under the installation prefix;
- sizes of `var/db`, `var/export`, `dataframe` and `var/logs`;
- pending export/publish work and pinned slices in health;
- repeated retry or reconcile diagnostics.

Do not increase `ioc.ingestion.concurrency` to drain a backlog: in 0.1.1 it is a
reserved seam and the ingestion channel remains synchronous. Future concurrency
would also require load testing of claim, ordering, recovery and SQLite's
single-writer behavior. First investigate slow copies, parser-heavy documents,
remote outages or an excessively long quiet period.

## Retention

There are two independent policies:

- `ioc.export.retention` removes old unpinned immutable slices;
- `ioc.maintenance.retention` handles leaf files in configured directories such
  as `done` and `failed`.

Leave maintenance retention disabled until investigation and backup periods are
agreed. Never configure a database directory, export root, inbox or processing
directory as a leaf-file retention target.

## Backup and recovery

Canonical business data and durable service state live in `var/db`. Generated CSV
projections can be rebuilt; the databases cannot. Back up the complete DB
directory together with the matching application/configuration release.

For upgrade, rollback and restore commands see the
[deployment guide](deployment.md). Test restore procedures on a non-production
host; a backup that has never been restored is not a verified recovery point.

## Routine checklist

- Service and local health are up.
- Inbox age and processing backlog remain within the expected window.
- Failed-file count is reviewed rather than silently accumulated.
- Disk space covers DB growth, export slices, logs and backups.
- Retention has not removed evidence required for investigation.
- Current version and config are recorded after every deployment.
- Optional remote sync is checked separately using the
  [remote storage guide](remote-storage-sync.md).
