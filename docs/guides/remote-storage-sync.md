# Remote storage synchronization (SMB)

[Русская версия](ru/remote-storage-sync.md)

This guide covers configuring and operating ordinary SMB fetch/publish, including
producer handoff, recovery and server access. Internal mechanics belong to
[sync](../dev/sync.md) and [event coordination](../dev/event-coordination.md).
Managed CSV import uses its own [guide](dataframe-import.md) and private ownership
namespace; sharing an endpoint does not grant ordinary fetch its mutation rights.

## How synchronization works

```text
remote source -> fetch -> local inbox -> ingestion -> canonical SQLite
canonical SQLite -> immutable export slice -> publish -> remote target
```

Fetch downloads whole source files and leaves remote originals unchanged. Ingest
parses them separately. Publish delivers already completed local slices; it does
not extract IOC or generate exports. Daemon schedules both flows. Oneshot CLI can
run a selected fetch/publish cycle against enabled JDBC-backed sync configuration.

### Identity, repeats and current limits

Fetch recognizes `(sourceId, remotePath, size, modifiedAt)`. Keep
`ioc.sync.fetch.sources[].name` stable for the same logical input even if you
rotate credentials or change its network address. Give a different input a new
name. Within a source, a replacement is distinct when the path, size or mtime
changes. Agree on producer retention so polling can observe completed files.

Landed names include a deterministic suffix before the original extension.
Review exact filename filters in ingestion and any external consumer of the inbox.
Extension globs such as `*.docx` continue to match. Coordinate inbox writers,
including manual fetch and the daemon. Concurrent attempts using the same scoped
destination can still race after the existence check. A crash between landing
and the ledger update can cause another delivery if ingest already moved the
first file. [OPS-12](../KNOWN-ISSUES.md#ops-12)

### Cut over the legacy fetch ledger

For an upgrade from service schema v14 or older, preserve a stopped, coordinated
recovery point for the old binary and both DBs. Rehearse the migration with the
candidate and a copy of the service DB. Schema v15 keeps all old records in
`remote_fetch_ledger_legacy`. It holds exact matches to old `FETCHED` rows until
their source is confirmed or replay is approved. New tuples continue normally.
`ioc sync fetch --source SOURCE --dry-run` reports held matches in its failed
count and logs `SYNC.FETCH_LEGACY_REVIEW_REQUIRED`. It leaves the inbox and fetch
records untouched. Opening a copied DB may apply the schema migration.

Inventory historical `FETCHED` tuples and match them to the configured sources
using the source listing and deployment or transfer records. Record each
confirmed owner and each replay candidate. A single source in today's config
does not establish historical ownership. A replay may create a new observation,
renew TTL or affect fields ordered by admission.

```sql
SELECT remote_path, remote_size, remote_mtime, local_path, fetched_at
FROM remote_fetch_ledger_legacy
WHERE status = 'FETCHED'
ORDER BY remote_path, remote_size, remote_mtime;
```

With the daemon and manual sync stopped, apply the reviewed decisions to the
migrated service DB in a transaction. Bind `:source_id`, `:remote_path`,
`:remote_size` and `:remote_mtime` to each reviewed entry. Verify that each insert
affects one row. An existing decision or scoped entry should be reconciled before
another update. Leave entries without a decision held and retain the legacy table.

For a confirmed historical owner, copy its existing success to that source only.

```sql
INSERT INTO remote_fetch_ledger (
    source_id, remote_path, remote_size, remote_mtime, status,
    local_path, attempts, last_error, fetched_at, updated_at)
SELECT :source_id, remote_path, remote_size, remote_mtime, status,
       local_path, attempts, last_error, fetched_at, updated_at
FROM remote_fetch_ledger_legacy
WHERE remote_path = :remote_path AND remote_size = :remote_size
  AND remote_mtime = :remote_mtime AND status = 'FETCHED';
```

For an explicitly approved redownload, record a replay decision for that source.

```sql
INSERT INTO remote_fetch_legacy_replay (
    source_id, remote_path, remote_size, remote_mtime, approved_at)
SELECT :source_id, remote_path, remote_size, remote_mtime,
       strftime('%Y-%m-%dT%H:%M:%SZ', 'now')
FROM remote_fetch_ledger_legacy
WHERE remote_path = :remote_path AND remote_size = :remote_size
  AND remote_mtime = :remote_mtime AND status = 'FETCHED';
```

Run a dry-run per source to check the remaining held counts before resuming
normal operation. Restore the coordinated pre-upgrade point when rolling back
to a binary that expects the old schema. [Deployment recovery](deployment.md#manual-application-rollback)

### Producer handoff and push notifications

The remote producer must write under an excluded temporary name, close it, then
rename it to an unused final name in the same filesystem/share. Fetch has no
remote quiet-period or before/after metadata validation. Writing directly to the
final name can download partial content; increasing debounce does not prevent it.
An interrupted copy may change size/mtime later, but a later complete download
does not undo any partial data already ingested.

`CHANGE_NOTIFY` is optional. A signal means “list this source again”; it does not
carry authoritative file contents or delivery identity. Its debounce coalesces
push triggers; scheduled/startup checks do not wait for a producer to finish.
Polling remains enabled. Choose `fetch.interval` for acceptable delay after a
lost signal, recovery and listing cost; no fixed interval is safe for every source.

A watch owns a dedicated long-lived SMB session/directory handle. Regular transport
uses the endpoint's cached session, shared with other operations where configured.
Each watch source adds demand even when endpoint names are equal. The watch renews
its session lease roughly every 30 minutes, independently of regular request/idle
timeouts, and nudges detection when re-established. Reconnect continues while the
daemon runs. Unsupported requested push capability fails startup; a later network
failure is handled by reconnect and polling, not proof that the server was qualified.

### Publication and consumers

Publish discovers completed slices and records a pair per slice/target. Local
catalog verification checks marker, manifest, membership and file hashes/sizes.
The SMB adapter uploads non-marker files to a private `.tmp-…` sibling directory,
uploads `_SUCCESS` last there, checks uploaded sizes and renames to the final slice
name. Consumers must ignore temporary directories.

A receiver accepts only a final directory whose `_SUCCESS` matches the manifest
hash, then verifies expected files, sizes and hashes. A marker alone is insufficient
end-to-end byte verification. Marker recovery can finalize a previous delivery
without downloading every remote CSV again. A mismatching existing marker fails
verification; an uncommitted final directory without one can be replaced. Keep
publish output isolated from unrelated data and external writers.

The delivery ledger has `PENDING`, `IN_PROGRESS`, `FAILED`, `SUCCEEDED` and
`ABANDONED`. The last two are terminal. An interrupted in-progress attempt becomes
retryable after its recovery threshold; it need not retry immediately on restart.
There is no public abandon/requeue CLI. A lost response can mean the slice is
already remote even though the local ledger has not completed.

Local retention waits for terminal delivery state for every currently configured
target of that profile. Missing/failed pairs can pin slices beyond age/count limits;
`ABANDONED` releases a pin without proving delivery. Removing a target changes this
obligation. The delivery-aware retention veto exists only while daemon sync/publish
is enabled with JDBC service storage. Turning off `ioc.sync.enabled` or
`ioc.sync.publish.enabled` removes it; preserve unresolved slices before that
change. Disabling publish is not a pause mechanism that keeps those pins intact.
Ordinary publish does not purge old committed remote slices; agree remote retention
with consumers separately.

## Configuration

Sync, fetch and publish are disabled by default; endpoint/source/target lists
are empty. Enable only the flows you configure.

This complete `ioc.sync` example merges with the application's built-in defaults.
It uses the existing `reputation-lists` export profile; configure that profile and
`ioc.export.enabled` for actual export generation. Daemon mode enables scheduling; the
JDBC service store is required for enabled sync use cases. ConfigData lists replace lower-priority
lists: provide complete endpoint/source/target entries, not a partial list patch.

```yaml
ioc:
  sync:
    enabled: true
    retry:
      max-attempts: 3
      backoff: 1s
      multiplier: 2.0
      max-backoff: 30s
      jitter: true
    endpoints:
      - name: intel-share
        transport: smb
        smb:
          host: files.example.org
          port: 445
          share: intel
          domain: CORP
          username: ${SMB_USER}
          password: ${SMB_PASSWORD}
          encryption: required
          connect-timeout: 10s
          request-timeout: 30s
          idle-timeout: 5m
    fetch:
      enabled: true
      interval: 1m
      sources:
        - name: incoming-intel
          endpoint: intel-share
          remote-path: /incoming
          include: [ "*.htm", "*.html", "*.docx" ]
          exclude: [ "*.tmp", "*.part", ".*" ]
          change-notify:
            enabled: false
            debounce: 3s
    publish:
      enabled: true
      interval: 5m
      targets:
        - name: reputation-delivery
          endpoint: intel-share
          remote-path: /out/reputation
          export-profile: reputation-lists
```

Select actual host/share/domain and paths; leave `domain` empty for a suitable
local account. `remote-path` is relative to the share, not a UNC path. Listing is
not recursive. Include/exclude match names; empty includes accept all regular
files, so choose explicit formats. Logical names/references must be valid and
unique; strict preflight rejects unknown keys/transports/profiles before I/O.
See the [configuration reference](configuration.md) for the complete contract.

### Retry and scheduling

| Setting | Default | Meaning |
|---|---|---|
| `retry.max-attempts` | `3` | Total calls including the initial call, for failures allowing immediate retry |
| `retry.backoff` / `multiplier` / `max-backoff` | `1s` / `2.0` / `30s` | Increasing delay before immediate retries, capped |
| `retry.jitter` | `true` | Randomizes those delays to reduce synchronized bursts |
| `fetch.interval` | `1m` | Periodic listing/recovery backstop, with or without push |
| `change-notify.enabled` / `debounce` | `false` / `3s` | Optional signal acceleration and coalescing delay |
| `publish.interval` | `5m` | Discovery/retry backstop for completed local slices |

Only `TRANSIENT` permits immediate micro-retry. `UNREACHABLE` and
`RESOURCE_EXHAUSTED` defer to a later cycle. Authentication, permission, missing
objects and security-policy failure do not benefit from immediate retry. Later
cycles can encounter unresolved failures again; fix persistent causes rather than
raising attempt counts. Watch reconnect uses the backoff family but continues
without a lifetime `max-attempts` cap. Event acceleration does not guarantee
instant publication: admission, backlog and a running transaction can delay it.

### Endpoint connection policy

| Setting | Default | Scope |
|---|---|---|
| `port` | `445` | TCP port for both regular and dedicated watch connections |
| `encryption` | `required` | SMB3-only negotiation plus effective session encryption checked before share I/O |
| `connect-timeout` | `10s` | TCP connection; not a whole-cycle or DNS deadline |
| `request-timeout` | `30s` | One regular SMB request, not an idle watch wait |
| `idle-timeout` | `5m` | Lifetime of an unused cached regular client, independent of the watch lease |

`preferred` and `disabled` explicitly permit weaker boundaries; they are not fixes
for an endpoint that should provide required encryption. Unsupported
`read-timeout` produces a migration hint. Legacy `encrypt` is a temporary rollback
alias; do not supply it together with `encryption`. New configuration uses only
the latter. Choose timeouts from observed network/server latency and recovery
requirements; idle lifetime can be below a known firewall timeout without proving
that every interrupted session will be detected immediately.

## Operation, failures and health

### Permissions and credentials

| Location | Ordinary sync account | Other identities |
|---|---|---|
| Source directory | Read/list/traverse, notification access if push is enabled; no write/delete | Producer writes and renames complete files |
| Publish target | Create/write/read/stat/list, rename and delete within its owned namespace | Consumers read/list; no modification |

Provision the share, parent traversal and filesystem ACLs as administrator. Generic
sync does not create shares or perform a full negative ACL audit on startup.
Notification support/permissions require a target check. Do not share the ordinary
source directory with managed-import ownership paths.

Supply credentials through environment placeholders. The installed systemd
EnvironmentFile is `etc/ioc-extractor.env`, mode `0640`; it is not a shell script.
Rotate credentials according to account policy, update that file and restart;
there is no hot rotation. Endpoint credentials remain in memory for reconnect,
although temporary character arrays are cleared. Keep passwords out of CLI
arguments, committed YAML and diagnostics.

### Failure interpretation

| Situation | Expected behavior / operator action |
|---|---|
| Network unavailable or server resources exhausted | Later cycles/reconnect can recover; inspect persistent backlog and server capacity |
| Authentication, permission or encryption policy failure | Correct account/ACL/policy; repeated retries cannot repair it |
| Source changes during download | No stable remote snapshot is promised; enforce producer handoff rather than relying on deduplication |
| Crash during fetch | Incomplete staging is hidden; an already landed file may be fetched again if ledger closure was lost |
| Crash/lost response during publish | Retain local slice/ledger; matching remote marker can complete recovery |
| Marker mismatch or missing/corrupt local slice | Preserve evidence and investigate binding/content; do not delete a marker to force overwrite |
| Daemon offline for a long period | Reconcile available source files and retained slices; removed inputs or manually deleted pins cannot be reconstructed |

The `sync` health view exposes source/target observations, detection timing,
watch state, endpoint lanes and `publishPending`/`publishInProgress`/`publishFailed`.
An untouched optional source/target can be `UNKNOWN`; it has not passed a probe.
Watch reconnect has a grace window before degradation. A transient source outcome
can be `DEGRADED`, while a durable `FAILED` publish pair makes sync `DOWN` until
resolved. Aggregate status and a per-operation observation are different views.

`smbTransport` reports application-owned connections/sessions/tree connections,
`pooledSessions`, `activeWatchSessions`, `plannedSteadySessions`, failures and
resource exhaustion. Planned steady demand is not a configured server limit or
available headroom. Use server monitoring for total usage, quotas and other clients.
Do not measure a limit by opening connections until rejection.

Use [daemon operations](daemon-operations.md) for logs/restart/backup. Application
`event.action` identifies `sync_fetch_start`/`sync_fetch_complete` and
`sync_publish_start`/`sync_publish_complete`; read diagnostic/severity and durable
outcomes too. Empty cycles are normally DEBUG. Preserve owned recovery state and
pinned slices instead of deleting them to reduce backlog.

### Manual CLI

Examples use the installer's `/opt/ioc-extractor` prefix; substitute your installed
prefix (`deploy-local.sh` defaults to `/srv/ioc-extractor`). The launcher requires
sudo and loads the configured service environment. Coordinate **mutating** commands
with the daemon: a separate process does not share its endpoint exclusion.

```bash
sudo /opt/ioc-extractor/bin/ioc sync --help
sudo /opt/ioc-extractor/bin/ioc sync fetch --source incoming-intel --endpoint intel-share --dry-run
sudo /opt/ioc-extractor/bin/ioc sync publish --profile reputation-lists --target reputation-delivery --endpoint intel-share --dry-run
sudo /opt/ioc-extractor/bin/ioc sync all --source incoming-intel --profile reputation-lists --target reputation-delivery --endpoint intel-share --dry-run
sudo /opt/ioc-extractor/bin/ioc health --component sync --json
sudo /opt/ioc-extractor/bin/ioc health --component smbTransport --json
```

Remove `--dry-run` only for an intended mutation. Fetch dry-run lists remote
metadata but does not download; publish dry-run inspects local slices/ledger and
does not connect to the target to prove write rights. Full CLI composition can
initialize/migrate the service DB even during dry-run. Use an isolated configuration
and workspace to keep rehearsal writes out of operational storage; the flag alone
is not a sandbox.

`sync all` validates both selections first, then fetches and publishes **existing**
slices. It neither runs nor waits for ingest/export of the newly fetched document.
A returned failure count gives sync exit `1`, zero failures `0`; setup, listing or
selection exceptions can also fail the command. Empty work with exit `0` does not
prove remote delivery or ACLs. For health, only `UP` returns `0`; parsed non-UP
(including `DEGRADED`/`UNKNOWN`) returns `1`, request/parse failure returns `2`.

## Preparing an SMB server

These are templates for a **new dedicated tree**, with administrator rights.
Provision the named producer/consumer groups and accounts first. Use a separate
monitoring identity for administrative queries; the application needs no server
administration rights. Existing trees require an ACL migration that preserves
needed access, rather than applying these examples blindly.

### Linux / Samba

For a Debian/Ubuntu-style host with Samba installed, create the service account
and the dedicated directory tree. Accounts/groups `producer` and `consumers`
must already exist. The producer must create group-readable files, including
when it writes outside Samba.

```bash
sudo groupadd iocsync
sudo useradd -M -s /usr/sbin/nologin -G iocsync svc-ioc
sudo smbpasswd -a svc-ioc
sudo install -d -o root -g root -m 0755 /srv/intel /srv/intel/out
sudo install -d -o producer -g iocsync -m 2750 /srv/intel/incoming
sudo install -d -o svc-ioc -g consumers -m 2750 /srv/intel/out/reputation
```

The output group has read/traverse, not group write. A minimal dedicated-server
configuration follows; integrate it with the server's existing policy. Comments
are on separate lines so they cannot become part of parameter values.

```ini
[global]
    server min protocol = SMB3_00
    server smb encrypt = required
    change notify = yes
    kernel change notify = yes

[intel]
    path = /srv/intel
    browseable = no
    read only = yes
    write list = producer, svc-ioc
    valid users = producer, svc-ioc, @consumers
    create mask = 0640
    force create mode = 0640
    directory mask = 2750
    force directory mode = 2750
```

Share write permission does not bypass filesystem rights. Parents must permit
traversal; setgid directories and creation modes keep consumer/source groups
readable. Review ACLs/umask for local producers separately. Notification behavior
for local writes depends on the server/filesystem backend; verify it rather than
assuming every producer path emits push. Polling still covers retained files.
The [Samba parameter reference](https://www.samba.org/samba/docs/current/man-html/smb.conf.5.html)
owns parameter semantics; use the installed server version's reference too.

Validate configuration before reload. The following queries inspect configuration
and current share activity; JSON availability depends on the installed version.

```bash
sudo testparm -s
sudo testparm -s --section-name='intel' --parameter-name='max connections'
sudo smbstatus --shares --json
```

Samba `max connections = 0` means no configured share limit; current share
connections are not the same as sessions per connection. Scope the firewall to
TCP 445 from application hosts (or the deliberately selected port). On an SELinux
host, label the dedicated tree using the distribution's supported Samba policy
and validate access; disabling enforcement is not a prerequisite.

### Windows Server

Provision service/producer accounts and the consumers group according to local or
AD policy. Deny interactive logon to the service identity and rotate its credential.
For this fresh example, use an administrator PowerShell and a previously absent
`D:\intel`. Root access for service/producer/consumers is traversal/read only;
SYSTEM and Administrators retain inherited full access below it.

```powershell
New-Item -ItemType Directory -Path D:\intel
icacls D:\intel /grant:r "*S-1-5-18:(OI)(CI)F" "*S-1-5-32-544:(OI)(CI)F" "svc-ioc:RX" "producer:RX" "consumers:RX"
icacls D:\intel /inheritance:r
New-Item -ItemType Directory -Path D:\intel\incoming, D:\intel\out\reputation
icacls D:\intel\incoming /grant:r "svc-ioc:(OI)(CI)RX" "producer:(OI)(CI)M"
icacls D:\intel\out /grant:r "svc-ioc:(OI)(CI)RX" "consumers:(OI)(CI)RX"
icacls D:\intel\out\reputation /grant:r "svc-ioc:(OI)(CI)M" "consumers:(OI)(CI)RX"
New-SmbShare -Name intel -Path D:\intel -ChangeAccess svc-ioc,producer -ReadAccess consumers -EncryptData $true
```

Check every native command's exit code and effective ACL before exposing the share;
stop on a failed step. `/grant:r` replaces explicit grants for named identities,
not all inherited permissions. This example removes inherited broad grants only
at the **fresh** root and does not sanitize arbitrary existing descendants.
The [icacls reference](https://learn.microsoft.com/en-us/windows-server/administration/windows-commands/icacls)
and [New-SmbShare reference](https://learn.microsoft.com/en-us/powershell/module/smbshare/new-smbshare)
explain filesystem and share permissions separately. Group memberships and inherited
rights still need an effective-access test. Disable SMB1 and restrict firewall
SMB-In to the application hosts under server policy.

```powershell
Get-SmbShare -Name intel | Select-Object Name, EncryptData, ConcurrentUserLimit, CurrentUsers
Get-SmbServerConfiguration | Select-Object MaxSessionPerConnection, EnableSMB1Protocol
(Get-SmbSession | Measure-Object).Count
```

Share users, sessions and `MaxSessionPerConnection` are different quantities.
Compare the intended application demand with the actual server limits using an
administrative monitoring identity. NAS platforms need the same functional,
encryption, ACL and notification checks; do not assume Windows behavior.

## Qualification and checklists

For a dedicated test source/target, verify with the actual application identity:
read/list source succeeds; source create/overwrite/rename/delete fails; target
create/write/read/rename/delete succeeds. Test with a separate producer identity
that it cannot modify publish output, and a consumer that cannot create or replace
files there. Validate producer permissions separately from service permissions.

A read probe from a host with a current Samba client can request encryption
explicitly (password prompted, not passed on the command line):

```bash
smbclient //files.example.org/intel -U svc-ioc --client-protection=encrypt -c 'cd incoming; ls'
```

See the [smbclient reference](https://www.samba.org/samba/docs/current/man-html/smbclient.1.html).
This probe does not prove application negotiation, negative permissions, publication
or crash recovery. Use an isolated end-to-end slice and an independent consumer
hash check. Never qualify by modifying a production source directory.

Before enabling, record exact binary/config/server identity, confirm complete
producer handoff, disjoint names/paths, environment credentials, required encryption
and source/target ACLs. Check local disk, DB/ledger backup and pinned-slice capacity.
Dry-run is selection/read evidence only; zero pending slices is not a publish test.

After enabling, verify a synthetic source reaches canonical storage and a completed
slice reaches the final remote path with matching manifest/files. Check durable
ledger outcome, source/target health and capacity signals. If push is enabled,
verify ACTIVE state, actual signals, reconnect and continued polling. Record any
unsupported capability explicitly. Opt-in external suites and required evidence
follow [TESTING](../TESTING.md); an offline skip is not live SMB qualification.

| Symptom | First checks |
|---|---|
| `SYNC.FETCH_LEGACY_REVIEW_REQUIRED` | Review the exact old tuple for the named source and record a cutover decision. [Cutover](#cut-over-the-legacy-fetch-ledger) |
| Two fetched outcomes, one inbox file | `OPS-12`: same final name and concurrent writers; retain logs/ledger before retry |
| Persistent DOWN | Component details, failed delivery pairs, credentials/ACL/encryption and local slice integrity |
| Resource exhaustion / smbTransport DEGRADED | Planned vs owned demand and server quotas/current usage; avoid reconnect storms |
| Push enabled, latency follows polling | Watch state/signals, notification capability and producer write path |
| Partial source data | Producer wrote directly to the final name; debounce is not a completion barrier |
| Slices pinned beyond retention limits | Missing or nonterminal delivery obligations; do not remove them manually |
