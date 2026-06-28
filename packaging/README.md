# packaging

Installing and operating **ioc-extractor** as a `systemd` daemon on Debian 11+.
Release `0.1.0`.

## Contents

| File | Purpose |
|---|---|
| `install.sh` | From-scratch installer (root): JDK 21 (manual), user, directories, jar, config, systemd unit. |
| `uninstall.sh` | Stop/remove the service; `--purge` also deletes the directory and the user. |
| `templates/ioc-extractor.service` | Unit template; placeholders `@PREFIX@/@VERSION@/@JAVA_BIN@/@USER@/@GROUP@` are substituted at install time. |
| `templates/application.yml` | Production override (daemon mode); deployed to `<prefix>/etc/application.yml`. |
| `templates/ioc-extractor.env` | `EnvironmentFile` with `JAVA_OPTS`. |

**Layer rule:** deployment material only (shell + templates), no Java code or
business logic. Daemon mode and configuration are documented in
[docs/ingestion.md](../docs/ingestion.md) and
[application.yml](../bootstrap/ioc-app/src/main/resources/application.yml).

## Java 21 — manual install only

Debian 11 repositories do not ship JDK 21, so the installer installs **Temurin 21
from a tarball** (no apt repositories are added) into `<prefix>/jdk` —
self-contained:

- offline host: `--jdk-tarball /path/OpenJDK21-jdk_x64_linux_hotspot.tar.gz`;
- host with internet: the tarball is downloaded from Adoptium automatically (or `--jdk-url`);
- if the host already has `java ≥ 21`: `--system-java`.

## Layout (single-dir)

```text
<prefix>/                         # default /opt/ioc-extractor
├── jdk/                          # Temurin 21 (installed manually)
├── lib/ioc-app-0.1.0.jar
├── etc/application.yml           # override (operator-editable)
├── etc/ioc-extractor.env         # JAVA_OPTS + optional SMB credentials
├── var/
│   ├── db/                       # ioc-dataframe.db + ioc-service.db (+ WAL/SHM)
│   ├── export/                   # immutable completed export slices
│   ├── inbox/ processing/ done/ failed/
│   └── ledger/ logs/
└── dataframe/                    # generated CSV projections + lookup seeds
```

The service `WorkingDirectory` is `<prefix>`, so the application's relative paths
(`./var/...`, `./dataframe/...`) resolve inside the install directory.

## Systemd hardening

The unit runs the daemon as a dedicated non-root user with no Linux capabilities
(`CapabilityBoundingSet=` and `AmbientCapabilities=` are empty). The filesystem is
read-only except `<prefix>/var` and `<prefix>/dataframe`; private `/tmp` and
private device visibility are enabled; kernel module/log/tunable, clock, hostname,
namespace, keyring, IPC, realtime scheduling, process personality and
foreign-architecture syscall surfaces are restricted. `StartLimitIntervalSec=5min`
and `StartLimitBurst=10` prevent a tight restart loop when configuration,
permissions or paths are broken.

Application/JDK/config files are owned by `root`; only `var` and `dataframe` are
owned by the service user. `UMask=0027` and mode `0640` on the environment file
keep SQLite data, generated artifacts and SMB credentials unavailable to other
users. The service needs no CIFS mount and no `CAP_SYS_ADMIN`: smbj opens an ordinary
outbound TCP connection, normally to port 445. `AF_UNIX/AF_INET/AF_INET6` are the
only allowed socket families. Egress IPs are intentionally not hard-coded in the
generic unit because SMB endpoint hostnames and addresses are operator-owned and
may change through DNS; enforce a host-specific egress allowlist in a systemd
drop-in or firewall where the deployment has stable target addresses.

Core dumps are disabled (`LimitCORE=0`) because source documents and extracted IOCs
may be sensitive. `LimitNOFILE=65536` leaves room for file-polling and logging
handles, while `TasksMax=512` caps runaway JVM thread growth without constraining
the current daemon workload.

Resource controls are intentionally conservative: `MemoryHigh=768M` and
`MemoryMax=1G` sit above the default `-Xmx512m` heap from `ioc-extractor.env`,
leaving room for JVM native memory while still bounding the process. If operators
raise `-Xmx`, they must raise these cgroup limits together. `CPUQuota=200%` limits
the daemon to roughly two CPU cores. `IOAccounting=true` and `IOWeight=100` enable
I/O accounting and a neutral best-effort weight; hard I/O bandwidth caps are left
unset until there are production measurements.

## Install

```bash
# 1) build the release jar (requires JDK 21; see the root README)
./mvnw -q -DskipTests package        # -> bootstrap/ioc-app/target/ioc-app-0.1.0.jar

# 2) install (the jar is auto-discovered next to the script or in target/)
sudo packaging/install.sh                         # prompts for the directory (default /opt/ioc-extractor)
sudo packaging/install.sh --prefix /opt/ioc-extractor
sudo packaging/install.sh --jdk-tarball /tmp/temurin21.tar.gz   # offline host
sudo packaging/install.sh --jar /path/ioc-app-0.1.0.jar --no-start
```

The installer is **idempotent**: re-running upgrades the jar and the unit; an
existing `etc/application.yml` is not overwritten (a `*.new` is written next to it)
unless `--force` is given. The installer **refuses** to install into a directory
containing `pom.xml`/`.git` (a source tree) without `--force`.

During an upgrade the installer stops an active service before replacing the jar.
Legacy databases at `var/ioc-service.db` and `dataframe/ioc-dataframe.db` are moved,
together with WAL/SHM sidecars, into `var/db`. If both old and new primary files
exist, installation stops for manual reconciliation instead of selecting one.

The optional `--lookup-seed PATH` supplies `masks_list.csv`. When installation is
run from the repository, the other current lookup seeds are discovered by their
canonical names (`ip_list.csv`, `hashes_list.csv`, `address_blacklist.csv`). Existing
operator lookup files are never overwritten.

## SQLite state and backup

Both databases are durable state and live together under `<prefix>/var/db`:

- `ioc-dataframe.db` is canonical business truth;
- `ioc-service.db` contains ingest/export/sync ledgers and recovery state.

The directory, not an individual `.db` file, is the writable/backup unit because
SQLite WAL mode may create adjacent `-wal` and `-shm` files. For a simple consistent
offline backup, stop the service and copy `var/db`; use SQLite's online backup API
if stopping is not acceptable. CSV files in `dataframe` are projections/reference
inputs and immutable delivery slices live separately in `var/export`.

## SMB sync

Remote sync is disabled by default. To enable it:

1. uncomment and adapt the `ioc.sync` example in `etc/application.yml`;
2. set `SMB_USER` and `SMB_PASSWORD` in `etc/ioc-extractor.env`;
3. keep the environment file `root:<service-group>` mode `0640`;
4. restart the service and inspect the `sync` actuator health component.

Fetch leaves the remote source untouched and atomically lands files in `var/inbox`.
Publish transfers only integrity-verified directories from `var/export`; delivery
progress is durable in `ioc-service.db`. The target share ACL normally needs read
for fetch and create/write/rename for publish. Delete permission is not required by
the v1 flow. Details: [docs/sync.md](../docs/sync.md).

## Operate

```bash
systemctl status ioc-extractor
journalctl -u ioc-extractor -f                 # ECS-JSON logs (+ <prefix>/var/logs/)
cp report.htm /opt/ioc-extractor/var/inbox/    # submit a source for processing
ioc health                                     # daemon health as a table (exit 0/1/2)
ioc health --json                              # raw JSON for scripts
```

The daemon exposes `/actuator/health` and `/actuator/info` on `127.0.0.1:8081`
(loopback only; `server.port`/`server.address` to change). `ioc health` queries it
and exits `0` UP / `1` DOWN / `2` unreachable (usable as a probe); raw
`curl -s http://127.0.0.1:8081/actuator/health` also works. Details:
[docs/dev/0010-health-actuator.md](../docs/dev/0010-health-actuator.md).

Flow: `var/inbox` → (quiet-period) → `var/processing` → `var/done` (or `var/failed`),
IOC rows are written directly into canonical `var/db/ioc-dataframe.db`, and the CSV
projections configured in `application.yml` are refreshed from it. Export emits
immutable, manifest-verified slices under `var/export/<profile>/<slice>/`; optional
SMB publish delivers those slices independently per configured target.

After editing `etc/application.yml`: `systemctl restart ioc-extractor`.

## Uninstall

```bash
sudo packaging/uninstall.sh                 # remove the service, keep data
sudo packaging/uninstall.sh --purge         # + delete the directory and the ioc user
```

## Out of scope (0.1.0)

A `.deb` package with maintainer scripts; OS-level log rotation (currently the
built-in Logback rolling appender).
