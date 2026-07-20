# Guide: working with remote storage (SMB sync)

A practical guide for administrators and developers: how synchronization with
remote storage works, how to configure it, how it survives failures, and how to
properly prepare an SMB share on Linux and Windows.

This document is deliberately written "from above": it explains system behavior
and configuration without tying itself to the internal implementation.
Architectural details live in [../sync.md](../dev/sync.md).

> Русская версия: [ru/remote-storage-sync.md](ru/remote-storage-sync.md).

## Contents

1. [How sync works](#1-how-sync-works)
2. [Configuration reference and choosing values](#2-configuration-reference-and-choosing-values)
3. [Permissions, failures, recovery](#3-permissions-failures-recovery)
4. [Setting up an SMB share on Linux and Windows](#4-setting-up-an-smb-share-on-linux-and-windows)
5. [Checklists](#5-checklists)

---

## 1. How sync works

### 1.1 The big picture

Sync consists of two independent data flows between the application and a
remote SMB share:

```text
                 FETCH (collecting sources)
remote share ─────────────────────────▶ ./var/inbox ──▶ regular ingest ──▶ SQLite

                 PUBLISH (delivering results)
SQLite ──▶ export slice (./var/export) ────────────────▶ remote share
```

- **Fetch** periodically looks into a remote directory, finds new files and
  downloads them into the local inbox. From there the file is picked up by the
  regular ingest daemon — sync itself never parses anything and never extracts
  IOCs.
- **Publish** delivers **immutable export slices** to the share — directories
  with CSV artifacts, a manifest and a `_SUCCESS` marker, produced by the
  export subsystem. Publish does not assemble data itself — it only copies
  already-verified slices.

Both flows run on a schedule in daemon mode only; in oneshot mode the same
operations are available manually through the CLI (`ioc sync fetch|publish|all`).

### 1.2 Three principles that explain all of the behavior

**1. Durable idempotency through ledgers.** Every downloaded file and every
delivered "slice × target" pair is recorded in a local database. Therefore:

- completed fetches are not intentionally downloaded again;
- the same slice is never delivered to the same target twice;
- after a restart or a crash the system does not start from scratch — it
  continues from where it stopped, checking against the ledger.

Fetch is an at-least-once transfer around a narrow crash window: the local file
is made visible before the durable fetch record is closed. A process crash
between those steps can download the object again under a collision-safe local
name. Canonical ingest deduplication prevents duplicate business rows, but the
transport does not claim distributed exactly-once delivery.

For fetch, "the same file" means the triple **path + size + modification
time**. If a file on the share changed (was appended to, re-uploaded) — that is
a *new* object and it will be downloaded again. This is deliberate: a
re-uploaded report under the same name is new data.

**2. Polling is the source of correctness, events are an accelerator.**
Periodic polling on a schedule is guaranteed to find everything that appeared
on the share, even if all the "fast" mechanisms failed. Push notifications
(SMB CHANGE_NOTIFY, see 1.4) only reduce latency — losing them affects nothing
except reaction speed.

**3. Atomicity at every boundary.** A file is never visible "half-way":

- fetch downloads into a hidden staging file and only then atomically renames
  it to the final name in the inbox;
- publish copies the slice into a temporary remote directory and writes the
  `_SUCCESS` marker as the very last step — a consumer that waits for the
  marker will never see a partially copied slice.

### 1.3 Fetch step by step

1. On a timer (`fetch.interval`) or on a push signal, the system requests the
   file listing of the remote directory.
2. The listing is filtered by the `include`/`exclude` masks.
3. Everything already downloaded (per the ledger) or currently being
   downloaded is dropped.
4. The rest is downloaded: staging file → atomic rename into the inbox →
   ledger record.
5. If the name in the inbox is taken by another file — the new one gets a
   suffix; nothing is ever overwritten.

Fetch **never modifies the remote share**: it does not delete, move or rename
source files. It does not need write permissions.

### 1.4 Push acceleration: SMB CHANGE_NOTIFY

`change-notify` can be enabled per source. The application then keeps a
persistent connection to the share and asks the server to report directory
changes. It works like a **doorbell**: any notification (file created,
modified, renamed, deleted — does not matter) means one thing — "check the
directory now". The system waits a short pause (`debounce`, so that a series
of notifications from one file collapses into a single check) and runs the
regular detection cycle from 1.3.

What an administrator should know:

- it is an **option for latency, not for correctness**: if the watch
  connection breaks, the system reconnects with an increasing backoff, while
  timer-based polling keeps working;
- the watch uses its own long-lived SMB session with an open directory handle.
  Fetch/publish/list/upload/download operations use the regular SMB transport
  session. Seeing two sessions for the same account on the file server is
  therefore normal: the watch session usually has one open handle, while the
  regular session may have zero opens and only stays cached until
  `idle-timeout`;
- the watch session is deliberately recreated roughly every 30 minutes
  (protection against connections that died silently behind a firewall/NAT);
  after every reopen the directory is checked forcibly, so the window between
  sessions is not lost. This 30-minute watch lease is separate from
  `request-timeout` and `idle-timeout`;
- with push enabled and stable, the polling interval can be made sparse
  (10–15 minutes, or even about an hour if that is an acceptable worst-case
  delay after a lost push) — this is where the main traffic and load savings
  come from;
- if push is enabled for an endpoint that does not support it, the application
  **does not start** (fail-fast): since the operator explicitly requested the
  capability, silently degrading to polling is not acceptable.

### 1.5 Publish step by step

1. The export subsystem produces local slices on its own schedule
   (`./var/export/<profile>/<slice>/…` + manifest + `_SUCCESS`).
2. Publish learns about a completed slice immediately (via an internal event)
   and — as insurance — periodically reconciles the slice directory against
   the delivery ledger (`publish.interval`).
3. For every "slice × target" pair a record is kept: `PENDING → IN_PROGRESS →
   SUCCEEDED` (or `FAILED`, retried on the next cycle).
4. Copying: temporary remote directory → byte-for-byte upload of the files →
   the `_SUCCESS` marker last.
5. After a crash mid-delivery, a retry is safe: it first checks whether a
   valid `_SUCCESS` from the previous attempt already exists on the share.

A local slice is not removed by retention until it has been delivered to
**all** configured targets.

---

## 2. Configuration reference and choosing values

Everything lives under `ioc.sync.*`. The feature is disabled by default
(`ioc.sync.enabled: false`). A full configuration example is at the end of
`application.yml` (the `smb-example` profile) and in [../sync.md](../dev/sync.md).

### 2.1 Overall shape

```yaml
ioc:
  sync:
    enabled: true
    retry: { max-attempts: 3, backoff: 1s, multiplier: 2.0, max-backoff: 30s, jitter: true }
    endpoints: [ ... ]     # "where we connect to" — connections
    fetch:                 # "what we collect" — sources
      enabled: true
      interval: 1m
      sources: [ ... ]
    publish:               # "what we deliver" — targets
      enabled: true
      interval: 5m
      targets: [ ... ]
```

Mental model: an **endpoint** is a connection (server + share + account +
timeouts). A **source** and a **target** are directories on that connection
plus the rules for working with them. Several sources and targets may share
one endpoint.

### 2.2 `retry.*` — retrying failed operations

| Parameter | Default | What it does |
|---|---|---|
| `max-attempts` | 3 | How many times to retry a failed operation (a download, an upload) within one cycle |
| `backoff` | 1s | Pause before the first retry |
| `multiplier` | 2.0 | Every next retry waits N times longer |
| `max-backoff` | 30s | Pause ceiling |
| `jitter` | true | Random "smearing" of the pauses so retries of different operations do not hit the server in sync |

**How to choose.** The defaults fit almost everyone. Raising `max-attempts`
only makes sense on a known-unstable network (WAN, an overloaded VPN) — and
remember that a failure is not fatal: the next scheduled cycle repeats the
work anyway. Do not disable `jitter`: with mass retries it protects the server
from synchronized bursts. The same `backoff/multiplier/max-backoff/jitter`
drive the reconnect pauses of the CHANGE_NOTIFY watch session, but
`max-attempts` does not apply to the watch — it reconnects indefinitely while
the daemon is alive.

### 2.3 `endpoints[].smb.*` — the connection

```yaml
endpoints:
  - name: intel-share          # logical name referenced by sources/targets
    transport: smb
    smb:
      host: files.example.org
      share: intel
      domain: CORP             # empty for a local account or Samba without a domain
      username: ${SMB_USER}
      password: ${SMB_PASSWORD}
      encrypt: true
      connect-timeout: 10s
      request-timeout: 30s
      idle-timeout: 5m
```

| Parameter | Default | What it does and how to choose |
|---|---|---|
| `host` | — | File server name or IP. Standard port — TCP/445 |
| `share` | — | Share name (no slashes): `intel`, not `\\host\intel` |
| `domain` | — | AD domain of the account. Leave empty for Samba with local users |
| `username` / `password` | — | **Environment variables only** (`${SMB_USER}`). Secrets never reach logs or health output |
| `encrypt` | — | SMB3 session-level encryption. Always enable it if the server speaks SMB3 (see section 4). Disable only for legacy servers and only on a trusted network |
| `connect-timeout` | 10s | How long to wait for the TCP connection. LAN: 5s is enough. WAN/VPN: 10–15s. Note: DNS resolution is not included in this budget |
| `request-timeout` | 30s | Ceiling for **one regular SMB request**: reading a block, writing, listing a directory, opening/closing handles. Raise it if the server is slow, or the directory holds thousands of files and the listing does not fit. It is not a polling interval, not a fetch/publish schedule, and it does not limit the idle wait of `CHANGE_NOTIFY` push notifications |
| `idle-timeout` | 5m | How long to keep an idle **regular transport connection** before closing it. This connection is used by fetch/publish/list/download/upload operations; it is separate from the active `CHANGE_NOTIFY` watch session. Lower — more frequent reconnects (extra handshake/auth). Higher — the socket lives longer and a stateful firewall may silently drop it. Practical rule: **slightly below** the idle timeout of your firewall/NAT (a common factory setting is 5–30 minutes) |

Endpoint names must be unique; a reference to a non-existent endpoint or an
unsupported `transport` is rejected at startup, before the first network
operation.

### 2.4 `fetch.*` — sources

```yaml
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
```

| Parameter | Default | What it does and how to choose |
|---|---|---|
| `interval` | 1m | Automatic polling period for all sources. It is both the "detection frequency" (without push) and the scheduled "safety-net cycle" (with push). Without push: pick it by the acceptable data-arrival delay — 1–5 minutes is typical. With push enabled and stable: make it sparse — 10–15 minutes is conservative, about 1 hour is acceptable if a lost push may wait that long for the scheduled backstop. An operator can still run `ioc sync fetch` / `ioc sync all` at any time to trigger the same fetch cycle immediately |
| `remote-path` | — | Directory inside the share (forward slashes: `/incoming`). The listing is **not recursive** — nested directories are not scanned |
| `include` | — | Name masks to collect. List only the formats you actually need — it is the first filter against junk |
| `exclude` | — | Masks ignored **on top of** include. Always keep the producer's temporary names here: `*.tmp`, `*.part`, hidden `.*` — this is the protection against downloading half-written files |
| `change-notify.enabled` | false | Push notifications for this source (see 1.4). Enable per source, after confirming via health that the watch is stable |
| `change-notify.debounce` | 3s | The "quiet" pause after a signal before checking the directory. Higher — better collapsing of a notification storm, but every single file gets that delay. 2–5s for regular files; 5–10s if the producer writes large files slowly and does not follow the rename convention |

**Agreement with the data producer (important).** Ask whoever drops files onto
the share to write them under a temporary name first (`report.docx.part`) and
**rename** to the final name when done. A rename in SMB is atomic — the system
will never see a half-written file. If the producer cannot do that,
compensate: extend `exclude`, raise `debounce`, and remember that even a
partially downloaded file is not lost — the completed version has a different
size/mtime and will be downloaded again as a new object.

### 2.5 `publish.*` — targets

```yaml
publish:
  enabled: true
  interval: 5m
  targets:
    - name: reputation-delivery
      endpoint: intel-share
      remote-path: /out/reputation
      export-profile: reputation-lists
```

| Parameter | Default | What it does and how to choose |
|---|---|---|
| `interval` | 5m | Automatic period of reconciling "what is ready locally" × "what has been delivered". A completed slice leaves immediately via an event; the interval is the scheduled insurance against a lost event or a restart. 5m is a sane default; with stable event delivery it can be raised (for example to 1 hour) if that worst-case automatic delivery delay is acceptable. An operator can still run `ioc sync publish` / `ioc sync all` at any time to reconcile and publish immediately |
| `remote-path` | — | Directory the slices are delivered into. Slice subdirectories and temporary directories (during copying) will appear inside |
| `export-profile` | — | Which export profile to deliver (see `ioc.export.profiles`). The profile must exist — validated at startup |

### 2.6 Related settings outside `sync`

- `ioc.export.*` — schedule and contents of the slices that publish delivers
  (trigger/quiet-period, profiles, local slice retention).
- `ioc.ingestion.dirs.inbox` — where fetch puts downloads and where the daemon
  picks files up for processing (`./var/inbox` by default).

---

## 3. Permissions, failures, recovery

### 3.1 Least privilege

The application needs **different** permissions on different directories —
create one service account, but grant permissions precisely:

| Directory | Role | Service account permissions | Others |
|---|---|---|---|
| Source (`/incoming`) | fetch reads | **Read + list**. Write is NOT needed — fetch never deletes or moves anything | Data producer: write + rename |
| Target (`/out/...`) | publish writes | **Full set on the contents**: create files and directories, write, rename, delete (needed for the temporary directory and retries) | Consumers: read only |

CHANGE_NOTIFY needs no extra permissions — the same read + list rights on the
source directory are enough.

What the application does **not** do (don't ask it to): it does not create
shares, does not change ACLs, does not delete source files, does not clean the
target directory beyond its own temporary directories. Storage provisioning is
the administrator's job.

### 3.2 Secrets

- Login/password are passed via environment variables only
  (`${SMB_USER}` / `${SMB_PASSWORD}`).
- In the systemd deployment they live in `etc/ioc-extractor.env` with mode `0640`.
- The password never reaches logs, health output or thread names. Temporary
  character-array copies are cleared after use, while the endpoint credential
  remains in process memory for as long as the client factory may need to
  connect or reconnect.
- Password rotation = update the env file and restart the service. There is no
  hot rotation.

### 3.3 How the system survives failures

| Situation | What happens | What to do |
|---|---|---|
| Share unavailable (network, server reboot) | Operations fail, get classified as transient (`DEGRADED`), retried with growing pauses; the next scheduled cycle tries again. The push watch session reconnects indefinitely | Nothing: after the network recovers everything resumes on its own. Look at health if DEGRADED persists |
| Wrong password / no permissions | The error is classified as permanent — health goes `DOWN`, retries do not "heal" it | Fix the account/ACL. This is the only error class that always needs a human |
| Daemon restart mid-download | The unfinished staging file is invisible to ingest; the ledger has not marked the file as fetched → after startup the file is downloaded again | Nothing |
| Restart mid-delivery | The ledger record stays non-terminal → goes into retry. The retry first checks the remote `_SUCCESS`: if the previous attempt managed to finish copying — the record is simply closed without re-uploading | Nothing |
| File on the share modified while being downloaded | Downloaded "as is"; the modified version is a new triple (path, size, mtime) → will be downloaded separately right after. Deduplication further down the pipeline removes data duplicates | Ask the producer to follow the rename convention (see 2.4) |
| Push notifications lost (server-side overflow, disconnect) | Overflow is honestly reported by the server — the directory is checked immediately. Disconnect → reconnect + a forced directory check afterwards. Anything that "slipped through" is picked up by scheduled polling | Nothing |
| Daemon was off for a week | No "event queue" piles up. The very first polling cycle collects all the new files, publish delivers all undelivered slices | Nothing |

### 3.4 Monitoring: what to look at

The `sync` health contributor (daemon actuator, loopback `:8081`, and the
`ioc health` command) shows:

- status per source/target/endpoint: `UP / DEGRADED / DOWN / UNKNOWN`;
- push-watch state per source: `ACTIVE / RECONNECTING / DISABLED`, counters of
  signals, reconnects and re-arms, duration of the last directory check;
- the delivery queue: `publishPending / publishInProgress / publishFailed`.

Reading rules:

- `UNKNOWN` — no conclusive operation has run yet. This is normal immediately
  after startup for an optional source/target: startup does not perform an
  authentication or remote write probe solely to make health turn green;
- `DEGRADED` — a transient problem (network, overload): the system retries on
  its own. React only if the status persists beyond your SLA.
  A short `RECONNECTING` on the watch is normal after a real disconnect; it
  turns into `DEGRADED` only when reconnecting keeps failing longer than the
  grace window (60 seconds). Planned watch re-arms should normally return to
  `ACTIVE` quickly and increment the re-arm counter, not leave the watch
  permanently reconnecting;
- `DOWN` — a permanent problem (authentication, permissions, a delivery error
  in the ledger): needs intervention;
- `publishFailed > 0` — check the logs of the specific slice × target pair.

In the logs (ECS JSON) search by `event.action`: `sync_fetch_start|complete`,
`sync_publish_start|complete`. Empty cycles are logged at DEBUG, real work at
INFO, degradation at WARN, permanent errors at ERROR.

### 3.5 Manual intervention (CLI)

```bash
ioc sync fetch   [--source NAME] [--endpoint NAME] [--dry-run]
ioc sync publish [--profile NAME] [--target NAME] [--endpoint NAME] [--dry-run]
ioc sync all     [--source NAME] [--profile NAME] [--target NAME] [--endpoint NAME] [--dry-run]
ioc health [--component sync] [--json] [--url URL | --host HOST --port PORT]
```

`--dry-run` may read remote metadata to show what would be done, but it does not
change the share, the inbox or the ledgers. It is useful for validating
selection and read access; publish write/delete permission still needs an
end-to-end test on a dedicated target. A non-zero failure count yields exit
code 1 (script-friendly).

---

## 4. Setting up an SMB share on Linux and Windows

Common server requirements for any OS:

- **SMB2 minimum, SMB3 recommended** (encryption, reliable notifications).
  SMB1 must be disabled;
- TCP/445 open from the application host to the server;
- a dedicated service account for the application, without interactive logon;
- directory layout: separate incoming (`incoming`) and outgoing (`out/...`)
  directories — they have different permissions (see 3.1).

### 4.1 Linux (Samba)

Installation and the account:

```bash
apt install samba                    # Debian/Ubuntu (dnf install samba — RHEL)
groupadd iocsync
useradd -M -s /usr/sbin/nologin -G iocsync svc-ioc
smbpasswd -a svc-ioc                 # SMB account password (the same one goes into SMB_PASSWORD)
```

Directories and permissions (owner — the data producer, group — for access):

```bash
mkdir -p /srv/intel/incoming /srv/intel/out/reputation
# incoming: the producer writes, svc-ioc only reads
chown producer:iocsync /srv/intel/incoming
chmod 2750 /srv/intel/incoming
# out: svc-ioc writes, consumers read
chown svc-ioc:consumers /srv/intel/out/reputation
chmod 2770 /srv/intel/out/reputation
```

`/etc/samba/smb.conf`:

```ini
[global]
    server min protocol = SMB2_10        # SMB1 is always off
    smb encrypt = desired                # required — if ALL clients speak SMB3
    # Change notifications (needed for change-notify):
    change notify = yes                  # the default, but pin it explicitly
    kernel change notify = yes           # the inotify backend: also catches local writes
                                         # (rsync, scp, cron on the server itself)

[intel]
    path = /srv/intel
    browseable = no
    read only = yes                      # the baseline is read-only...
    write list = producer, svc-ioc       # ...only these accounts may write
    valid users = producer, svc-ioc, @consumers
    create mask = 0640
    directory mask = 0750
```

> A subtlety about notifications: `kernel change notify` is responsible for
> changes made **bypassing Samba** (a local process on the server, NFS,
> rsync). If the producer drops files that way — without this parameter the
> push notifications never reach the client, and the system lives on polling
> alone (not a breakage, just slower).

SELinux (RHEL/CentOS/Fedora):

```bash
semanage fcontext -a -t samba_share_t "/srv/intel(/.*)?"
restorecon -Rv /srv/intel
```

Firewall: `firewall-cmd --add-service=samba --permanent` (or open 445/tcp).

Verification from the application host:

```bash
smbclient //files.example.org/intel -U svc-ioc -c 'ls incoming\'   # listing — must work
smbclient //files.example.org/intel -U svc-ioc -c 'put test.txt incoming\test.txt'
                                                                   # and THIS must be DENIED
```

### 4.2 Windows Server

Share and permissions (PowerShell, as administrator):

```powershell
# Service account (or a domain one in AD — then set domain in the config)
New-LocalUser svc-ioc -Password (Read-Host -AsSecureString) -PasswordNeverExpires

New-Item -ItemType Directory D:\intel\incoming, D:\intel\out\reputation

# The share: grant broad access at the share level (Change), real restrictions live in NTFS
New-SmbShare -Name intel -Path D:\intel -ChangeAccess svc-ioc, producer -ReadAccess consumers
# SMB3 encryption for this share:
Set-SmbShare -Name intel -EncryptData $true
```

NTFS ACLs — precisely, per the permission matrix (3.1):

```powershell
# incoming: svc-ioc only reads and lists
icacls D:\intel\incoming /grant "svc-ioc:(OI)(CI)RX"
icacls D:\intel\incoming /grant "producer:(OI)(CI)M"
# out: svc-ioc fully manages the contents (create/write/rename/delete)
icacls D:\intel\out /grant "svc-ioc:(OI)(CI)M"
icacls D:\intel\out /grant "consumers:(OI)(CI)RX"
```

Server hygiene:

```powershell
# SMB1 — off, signing — on
Set-SmbServerConfiguration -EnableSMB1Protocol $false
Set-SmbServerConfiguration -RequireSecuritySignature $true
```

- Deny the service account interactive logon (secpol: "Deny log on locally").
- CHANGE_NOTIFY works out of the box on Windows, no extra setup needed.
- Firewall: allow 445/tcp from the application host (scope the "File and
  Printer Sharing (SMB-In)" rule to the host's address).

### 4.3 NAS and specialized storage

On NAS firmware (NetApp, EMC, QNAP, TrueNAS, etc.) SMB notifications are
supported unevenly: some do not deliver them for nested changes, some ship
with them disabled. The rule is simple: **enable `change-notify` only after a
staging check** — create a file in the directory and confirm via health (the
watch signal counter grows) that notifications arrive. If not — keep polling
with a comfortable interval; you lose nothing functionally.

---

## 5. Checklists

### Before the first start

- [ ] The share is reachable from the application host: `smbclient //host/share -U svc-ioc -c 'ls'`
- [ ] The service account has: read on the source, full rights on the target's
      contents, and **nothing** extra (verify that writing to the source is denied)
- [ ] SMB1 disabled on the server; `encrypt` decided (SMB3 → `true`)
- [ ] Secrets in env (`SMB_USER`/`SMB_PASSWORD`), not in yaml
- [ ] `exclude` contains `*.tmp`, `*.part`, `.*`; the rename convention is
      agreed with the data producer
- [ ] `idle-timeout` is below the idle timeout of the firewall between the
      host and the server
- [ ] `ioc sync all --dry-run` completes without errors

### After enabling

- [ ] `ioc health` / actuator: sources and targets are `UP`
- [ ] A test file dropped into the source shows up in `var/inbox` and passes ingest
- [ ] A test slice is delivered: the slice directory with `_SUCCESS` is on the
      share, health shows `publishFailed = 0`
- [ ] If `change-notify` was enabled: the watch is `ACTIVE`, the signal counter
      grows when a file is created; after that `fetch.interval` may be raised

### Symptom → diagnosis

| Symptom | Likely cause | Check |
|---|---|---|
| `DOWN` right after startup | Account/password/permissions | `smbclient` with the same account |
| `DEGRADED` in waves | Unstable network, firewall dropping idle connections | `idle-timeout` vs the firewall; WARN logs |
| Files arrive with a delay equal to the interval although push is enabled | Notifications do not arrive (NAS, writes bypassing Samba) | health: watch signals do not grow on file creation; see 4.1/4.3 |
| Application does not start with change-notify enabled | The endpoint does not support push | This is fail-fast by design: disable `change-notify.enabled` or fix the endpoint |
| Half-written files get downloaded | The producer writes directly into the final name | Rename convention; extend `exclude`; raise `debounce` |
| A slice is not leaving, retention does not clean | There is an undelivered target (`FAILED`/`PENDING`) | health `publishTargets`, logs of the slice × target pair |
