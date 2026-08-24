# Managed dataframe import

This guide explains how to operate contract-driven CSV import from a dedicated
local directory or SMB share. Import is disabled by default and writes to the
same canonical SQLite truth as ordinary extraction.

## Before enabling import

1. Back up `var/db`, the live configuration and the environment file.
2. Keep `ioc.lifecycle.validity.mode: fixed`. Import cannot start with lifecycle
   disabled because every accepted observation must receive one atomic validity
   outcome.
3. Give each trust level its own source and credential. The source authority
   profile is a hard ceiling for artifacts, routing, formula handling and merge
   policy.
4. Define one versioned contract for each accepted CSV shape. Recognition uses
   headers and declared aliases, never the filename or column order.
5. Validate representative valid, malformed, duplicate and ambiguous files
   before enabling intake.

Use the complete property reference in [configuration.md](configuration.md) and
start from the commented example in the production
[`application.yml`](../../packaging/templates/application.yml). Changing a
contract version or behavior requires a validated restart; runtime hot reload is
not supported.

## Choose the processing and update policies

- `mode: as-is` treats mapped CSV cells as final candidate values. Only explicit
  contract transforms run.
- `mode: processed` runs IOC carrier cells through the ordinary refang,
  extraction, classification and artifact mapping policies. Pipeline-derived
  identity/match/routing values replace imported copies; operator metadata such
  as score, source or description remains subject to its merge policy.
- `routing: target-only` is the safe default. Use `related-artifacts` only when
  the contract declares every related branch and the authority profile permits
  it.
- `fill-missing` is the safe merge default. Use `authoritative` only for a source
  that may replace non-null values and explicitly clear them with an empty cell
  or configured null literal.
- `renew-unchanged` independently controls whether a byte-equivalent accepted
  row renews TTL. Missing rows never delete, change or renew local records.

Every CSV row is atomic across its configured branches. Compound fields such as
URL plus IP in `address_blacklist`, or several hashes for one file, remain one
row. A different identity-bearing value is a new record.

## Validate without importing

Run preview against the same installed configuration and source allowlist:

```bash
/srv/ioc-extractor/bin/ioc import validate \
  --source trusted-local \
  --file /path/to/candidate.csv
```

Preview reads and plans the file but does not claim it, reserve an export slot or
write canonical/service state. A successful preview is advisory: real intake
revalidates the live catalog, source evidence and active database state.

## Submit a local delivery

Build and fsync the file outside the configured source directory, but on the
same filesystem, then atomically move the completed file into that directory.
Do not use a `.part` name inside the watched directory: filenames and suffixes
do not select a contract, so every stable regular file there is a delivery
candidate. Never place producer files in `processing`, `snapshots`, `staging`,
`terminal` or `quarantine`.

The service waits for stable metadata, atomically claims the file and creates a
private immutable snapshot before parsing. Each stabilized CSV is a distinct
delivery, including a byte-identical resubmission.

## Submit an SMB delivery

Use a dedicated directory on the configured share. The service account needs
list, read, rename/move and create-directory rights within that directory and
its private `.ioc-managed-import` namespace. Producer and consumer must be on
the same server-side filesystem so claim can use rename without copy/delete.
Prefer uploading into a producer-owned sibling staging directory and then using
one server-side rename into the configured source. If the producer must stream
directly into the source directory, its maximum write pause must stay below the
configured stability quiet period; increase that period when this cannot be
guaranteed.

Enable SMB encryption unless the network is trusted by another documented
control. `CHANGE_NOTIFY` reduces latency; complete listing remains enabled and
recovers notification loss, disconnects and restarts.

## Observe progress

```bash
/srv/ioc-extractor/bin/ioc import status
/srv/ioc-extractor/bin/ioc health
journalctl -u ioc-extractor --since -15m
```

Status shows aggregate state counts and the durable head sequence, state, age,
retry count/delay and safe diagnostic code. It intentionally omits IOC values,
source paths, filenames and digests. A retrying head holds FIFO order; later
deliveries do not overtake it.

The packaged low-latency preset uses import listing/stability/retry timings of
`2s/2s/2s`, export coalescing/backstop/max-cap of `1s/10s/30s`, ordinary ingest
polling/stability of `5s/2s`, and SMB notification debounce of `1s`. These values
favor event-driven response while preserving bounded correctness scans. Increase
the stability window for producers that cannot publish by atomic rename.

## Outcomes and recovery

- Successful deliveries move to the protected terminal area with a safe JSON
  report. Rejected deliveries move to quarantine.
- A malformed file, ambiguous contract or hard parser limit rejects the whole
  delivery. With `accept-valid`, an isolated invalid row does not discard other
  valid rows; `reject-delivery` is the strict alternative.
- Canonical promotion is one cross-artifact transaction. A durable dataframe
  receipt prevents duplicate mutation after a crash.
- Startup recovery runs before ordinary ingestion or import intake. Do not move,
  edit or delete private runtime files while recovery is active.
- Manual queue skipping and forced completion are deliberately unavailable.

To replay retained terminal evidence as a new occurrence:

```bash
/srv/ioc-extractor/bin/ioc import replay --delivery <delivery-id>
```

Replay never reopens the old terminal record. It receives a new delivery ID and
sequence and retains a causal link to the original.

## Incident checklist

1. Stop adding files; do not modify private managed-import directories.
2. Capture `ioc import status`, `ioc health` and recent service logs.
3. Confirm free space for `var/import`, both databases and their WAL sidecars.
4. Check source permissions, SMB reachability and whether the producer still
   holds or changes the file.
5. Fix configuration through `ioc-config check` and `ioc-config apply`; do not
   edit the live YAML in place.
6. Use replay only for a retained terminal delivery after the underlying cause
   is understood.

Internal mechanics and invariants are documented in
[the capability document](../dev/dataframe-import.md) and
[ADR-0024](../ADR/0024-managed-dataframe-import.md).
