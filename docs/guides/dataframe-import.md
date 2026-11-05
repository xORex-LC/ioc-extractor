# Managed dataframe import

[Русская версия](ru/dataframe-import.md)

This guide explains how to operate contract-driven CSV import from a dedicated
local directory or SMB share. Import is disabled by default and writes to the
same canonical SQLite truth as ordinary extraction.

## Before enabling import

1. Pause producers and stop the service before taking a coordinated recovery
   point: both databases and matching SQLite side files, owned recovery files,
   configuration, environment file and compatible binary. Follow the
   [deployment backup procedure](deployment.md); copying a live main DB without
   its WAL is not a consistent backup.
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
6. For every SMB source, pre-provision the private namespace and verify its ACLs
   with separate producer and service identities before starting the runtime.
   Qualify the intended server family: the positive service probe does not
   establish producer denial or replace the deferred external `OPS-8` check.

Use the complete property reference in [configuration.md](configuration.md) and
start from the commented example in the production
[`application.yml`](../../packaging/templates/application.yml). Changing a
contract version or behavior requires a validated restart; runtime hot reload is
not supported. Sources and contracts are lists: preserve the complete intended
lists when editing an overlay. A partial replacement can remove other sources
or the shipped aggregate contract; it does not append one item automatically.

## Choose the processing and update policies

- `mode: as-is` treats mapped CSV cells as final candidate values. Only explicit
  contract transforms run.
- `mode: processed` runs IOC carrier cells through the ordinary refang,
  extraction, classification and artifact mapping policies. Pipeline-derived
  identity/match/routing values replace imported copies; operator metadata such
  as score, source or description remains subject to its merge policy.
  Every processed contract must declare `processed-route` with a named plan,
  explicit input cells and authorized output fields, including when intake is
  disabled. Computed match/routing fields outside that output allowlist are not
  imported automatically. The
  [route activation guide](ioc-processing-routes.md#bind-a-processed-import-contract)
  shows the explicit input and output bindings.
- `routing: target-only` is the safe default. Use `related-artifacts` only when
  the contract declares every related branch and the authority profile permits
  it.
- `fill-missing` is the safe merge default. Use `authoritative` only for a source
  that may replace non-null values and explicitly clear them with an empty cell
  or configured null literal.
- `renew-unchanged` independently controls whether a byte-equivalent accepted
  row renews TTL. Missing rows never delete, change or renew local records.

### Presence and merge

| Input under the declared contract | Meaning for an existing record |
|---|---|
| Optional mapped header omitted | `ABSENT`: leave the field alone |
| Empty cell or exact configured null literal | `NULL`: clear only under an authorized clearing policy |
| Other text | `VALUE`: apply explicit transforms, validation and merge policy |

A short record is malformed, not ABSENT. Quoting `NULL` or an empty cell does
not escape the import null instruction. If literal text `NULL` must be retained,
choose a noncolliding null-literal contract; do not rely on export quoting rules.
Whitespace is a value unless explicit transforms remove it. The effective merge
policy is column, artifact, then contract default, bounded by source authority.
The [merge table](../dev/dataframe-import.md#cell-presence-merge-and-identity)
also covers `keep-existing`, `replace-non-null` and `reject-conflict`.

`renew-unchanged: false` suppresses an otherwise unchanged TTL confirmation,
not confirmation of an accepted public mutation. It does not turn missing rows
into deletions. A nonblank candidate for a configured ordered mutable field can
still request confirmation when its public text matches; the flag is not a
blanket TTL freeze. A repeated commit-recovery attempt never applies the delivery
as a new observation.

Every CSV row is atomic across its configured branches. Compound fields such as
URL plus IP in `address_blacklist`, or several hashes for one file, remain one
row. Final record and declared match keys determine whether active canonical
rows match. Multiple active matches reject the logical row. Changing the stable
primary key of a matched record is a conflict, not an automatic new insert.

Declare `columns[].validation` when the source must supply valid IOC values.
AS_IS and an empty transform list do not enable this check automatically:

| Validator | Accepted non-null cell |
|---|---|
| `bare-ip` | One bare IPv4 address |
| `clean-domain` | One clean domain or subdomain |
| `url-address` | One supported network address with URL/address detail |
| `network-address` | One supported IPv4, domain or URL, including detailed forms |
| `hash` | One MD5, SHA1 or SHA256 hash |
| `md5`, `sha1`, `sha256` | One hash of the named algorithm |
| `canonical-integer` | Signed 64-bit decimal integer with no leading zeros, plus sign, whitespace, fraction or exponent |

IOC validators inspect a refanged whole-cell candidate and allow blank padding;
they do not rewrite the admitted cell or perform host cleanup. Declare explicit
transforms when the stored format needs normalization. Validators do not bypass
formula policy. For example, `canonical-integer` accepts
`10` and rejects `010` or `10.0`; NULL remains NULL. Use the same admitted numeric
representation whenever an INTEGER field participates in a record or match key.

Within-file duplicates use the primary artifact's record key. KEEP_FIRST retains
the first admitted whole row and its requested slot; it does not compact the
slots of subsequent accepted rows. Across deliveries, the selected match keys
also control whether a row matches an existing record. Changing only duplicate
selection cannot introduce a new canonical identity. `coalesce` merges compatible
cell contributions and rejects conflicting groups; `last-nonempty` selects one
whole row using its configured selection column, without cell-by-cell merging.

Only an explicit requested-slot binding interprets an imported ID as a slot
request. It never becomes a trusted canonical row or lifecycle ID. Requests use
`requested-slot.profile`, which must be the
profile used to publish the artifact when those IDs must appear in that output.
`preserve-existing` keeps a matched survivor's slot even when its imported ID
differs. A new record requesting an occupied slot receives the lowest available
slot; the receipt and terminal report record the reassignment. These are separate
cases: `existing-record-policy` controls the survivor mismatch only. In an
already initialized registry, a matched lifecycle without a slot is rejected
under either policy; do not substitute its canonical ID. An eligible export can
first reconcile that assignment.

## Validate without importing

The import commands require enabled import and a complete catalog with fixed
lifecycle. The installed wrapper uses oneshot mode, so it does not open daemon
intake. For checks before enabling a live source, use a disposable configuration
and storage copy rather than enabling unqualified intake on the live daemon.
With the installed configuration and source allowlist, run:

```bash
sudo /srv/ioc-extractor/bin/ioc import validate \
  --source trusted-local \
  --file /path/to/candidate.csv
```

The validator reads, recognizes and maps the file. It does not claim the source,
reserve slots or promote canonical rows. The full command starts Spring and can
initialize/migrate configured storage and create workspace directories; it is
not a promise of zero filesystem writes. Use a stable regular non-symlink local
file outside managed inboxes. An SMB delivery can be previewed from a local
candidate copy with the corresponding source ID.

Preview returns `0` when every mapped row is valid and `3` for an invalid preview;
configuration, missing-file and startup failures are separate failures. A valid
preview does not perform active-DB matching, merge/slot planning or canonical
commit. Real intake rechecks the catalog, owned bytes and current canonical state.
Preview maps rows individually. It does not run within-delivery duplicate
reduction or detect COALESCE group conflicts, and its accepted-row count is not
the final staging or canonical-commit count.

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

Use a dedicated directory on the configured share. Managed import is a mutating
consumer: unlike ordinary `sync.fetch`, it claims, moves and eventually deletes
its own managed objects. Do not grant these rights to a read-only sync-fetch
source merely because both capabilities share an SMB endpoint/session pool.

Pre-create this exact namespace below each configured managed-import location:

```text
<source>/
└── .ioc-managed-import/
    ├── processing/
    ├── terminal/
    ├── quarantine/
    └── probe/
```

The application never creates these directories. Before candidate listing it
uses the service identity to create an empty reserved object in `probe`, rename
it through `processing` to `terminal`, and delete that exact regular file. A
missing directory or deterministic permission/object mismatch closes only that
source; a later reconcile reprobes it. This is a positive operation check, not
an ACL audit: the application cannot prove that the producer is denied access.

Use two distinct identities and enforce this minimum matrix on the server:

| Location / operation | Producer identity | Service identity |
|---|---|---|
| `<source>` publish completed regular file | allow create/write and atomic handoff; grant only the listing/read rights the producer actually needs | allow list/read and server-side rename into `processing` |
| `.ioc-managed-import` and all children | deny traversal, listing, read, write and delete | allow traversal and the exact probe/claim/disposition/retention operations |
| `probe` | no access | create empty regular file, rename out and exact delete for recovery |
| `processing` | no access | create by rename, read with write sharing excluded, rename out; no recursive delete |
| `terminal`, `quarantine` | no access | create by rename, inspect and delete exact regular managed object |

Producer and consumer must be on the same server-side filesystem so claim can
use rename without copy/delete.
Prefer uploading into a producer-owned sibling staging directory and then using
one server-side rename into the configured source. If the producer must stream
directly into the source directory, its maximum write pause must stay below the
configured stability quiet period; increase that period when this cannot be
guaranteed.

Keep the secure default `encryption: required`: it requires SMB3 and effective
session encryption. `preferred` and `disabled` explicitly accept a weaker
boundary and need an appropriate deployment decision; they are not aliases for
an enabled boolean. `CHANGE_NOTIFY` reduces latency; complete listing remains enabled and
recovers notification loss, disconnects and restarts.

### Samba example

Create the namespace as an administrator, then express the matrix with your
site's POSIX ACL groups. This example uses dedicated accounts and assumes the
share is configured to honor filesystem ACLs:

```bash
install -d -m 0770 -o ioc-service -g ioc-service /srv/ioc-import/inbox/.ioc-managed-import/{processing,terminal,quarantine,probe}
setfacl -m u:ioc-service:rwx,u:ioc-producer:-wx,m::rwx /srv/ioc-import/inbox
setfacl -m d:u:ioc-service:rw-,d:m::rw- /srv/ioc-import/inbox
setfacl -R -m u:ioc-service:rwx,u:ioc-producer:---,m::rwx /srv/ioc-import/inbox/.ioc-managed-import
```

Adapt owner/group/default ACLs to the server's Samba identity mapping. Test as
the producer that private traversal/list/read/write/delete all fail, and test as
the service account that the application capability gate becomes ready. Do not
infer producer denial from a successful service probe.

### Windows Server example

Create the four directories in the share's NTFS backing folder. Give the
service account `Modify` on `<source>` and the private subtree. Give the producer
only the source-folder rights needed to publish a completed file, then remove
inherited producer/group access from `.ioc-managed-import` (retain
Administrators/SYSTEM and the service identity). Use Advanced Security or
`icacls`; exact principals and inheritance flags are deployment-specific.

Verify with `runas` or separate sessions for both identities: the producer must
be unable to traverse or list the private subtree, while the service account
must pass the capability gate and a producer-created candidate must be claimable.
The application does not inspect or certify NTFS/Samba ACL policy.

The repository's opt-in `SmbManagedImportHardeningContractIT` performs this
two-identity proof against a pre-provisioned fixture. It never creates or
recursively deletes the namespace. See the
[external-test policy](../TESTING.md) and
[hardening contract](../../adapters/adapter-transport-smb/src/test/java/com/iocextractor/adapter/out/transport/smb/SmbManagedImportHardeningContractIT.java).
A property-gated skip is not a qualified server result. Supply credentials via
the fixture's environment variables, never Maven properties retained in reports.

## Observe progress

Known limitation [OPS-10](../KNOWN-ISSUES.md): the current SQLite status reader
can fail when the nonterminal head has no retry deadline. The import health
indicator uses the same reader, so it is also affected. Preserve the command
error and retained reports/logs; do not infer failed canonical commit or edit a
deadline/ledger row to hide the defect. These commands remain the intended
observation interface, but an error under this condition is not a successful
status result.

```bash
sudo /srv/ioc-extractor/bin/ioc import status
sudo /srv/ioc-extractor/bin/ioc health --component dataframeImport
sudo journalctl -u ioc-extractor --since -15m
```

The installed wrapper requires `sudo` and runs as the service identity with its
EnvironmentFile. These commands are local CLI processes; health queries the
running daemon's actuator, while import status reads the local service ledger.

Status shows aggregate state counts and the durable head sequence, state, age,
retry count/delay and safe diagnostic code. It intentionally omits IOC values,
source paths, filenames and digests. A retrying head holds FIFO order; later
deliveries do not overtake it. Status command exit `0` means the query completed,
not that the queue is empty or healthy. Its `Recovery complete` flag belongs to
the oneshot context and can be false while the daemon is healthy; use the
running daemon's `dataframeImport` health component for readiness.

`CLAIMING` means ownership acquisition; `STAGING` is preparation;
`PROMOTING` is around the canonical transaction; `FINALIZING` is convergence
of projection/report/source disposition after commit. A transient source or
retrying head can be `DEGRADED`; incompatible capability or failed recovery is
`DOWN`. Do not delete a head record to unblock later work.

The packaged low-latency preset uses import listing/stability/retry timings of
`2s/2s/2s`, export coalescing/backstop/max-cap of `1s/10s/30s`, ordinary ingest
polling/stability of `5s/2s`, and SMB notification debounce of `1s`. These values
favor event-driven response while preserving bounded correctness scans. Increase
the stability window for producers that cannot publish by atomic rename.

## Outcomes and recovery

- `SUCCEEDED` and `COMPLETED_WITH_ERRORS` publish a protected local terminal
  source/report unit; `REJECTED` uses quarantine. `accept-valid` can commit valid
  rows while reporting rejected ones as `COMPLETED_WITH_ERRORS`; partial success
  is not a rejected delivery. For SMB forward deliveries,
  retention deletes the exact remote terminal/quarantine regular object before
  local terminal/workspace/snapshot/receipt cleanup and deletes the durable
  ledger row last.
- A malformed file, ambiguous contract or hard parser limit rejects the whole
  delivery. With `accept-valid`, an isolated invalid row does not discard other
  valid rows; `reject-delivery` is the strict alternative.
- Canonical promotion is one cross-artifact transaction. A durable dataframe
  receipt prevents duplicate mutation after a crash.
- Startup recovery runs before ordinary ingestion or import intake. Do not move,
  edit or delete private runtime files while recovery is active.
- Manual queue skipping and forced completion are deliberately unavailable.
- Drain nonterminal deliveries before changing processing/validation semantics.
  An unsealed retry must reproduce its pinned contract; a changed policy can
  block the global lane. A sealed stage or canonical receipt has different
  recovery evidence. Changing YAML back does not undo canonical commits.

To replay retained terminal evidence as a new occurrence, replace the example ID
with `deliveryId` from its retained terminal report:

```bash
sudo /srv/ioc-extractor/bin/ioc import replay --delivery 'retained-delivery-id'
```

Replay never reopens the old terminal record. It receives a new delivery ID and
sequence, retains a causal link to the original and is source-detached: it does
not disposition or purge an SMB object. The active source allowlist and catalog
apply again; replay is not an exact re-execution of the old contract or rollback.
The terminal ledger entry and protected local source unit must still be retained.
A successful command confirms admission; the daemon completes processing later.
Use `--new-delivery` with a stable chosen ID when retrying the same replay request;
without it each invocation generates another occurrence.

Reports contain safe counts, logical CSV record numbers and diagnostic codes,
not raw IOC cells or producer paths. Warning details are bounded and are not an
exact count of every warning encountered. The defaults retain successful units
for `30d` and unsuccessful units, including `COMPLETED_WITH_ERRORS`, for `90d`.
A retention action `archive` archives the protected local unit; it does not
preserve an SMB forward source object, which is still purged first. Neither
retention nor replay deletes canonical records missing from an input file.

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
