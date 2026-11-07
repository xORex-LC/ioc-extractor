# Deployment, upgrade and rollback

[Русская версия](ru/deployment.md)

This guide describes host installation and lifecycle operations for an
ioc-extractor daemon. Debian 11 and 12 are the tested installer baselines. Other
systemd distributions are best effort and require operator validation.
This guide covers initial deployment and acceptance. [Daemon operations](daemon-operations.md)
owns routine intake, retry, retention and backup operation afterwards.

## Choose a deployment path

| Path | Use it for | Guarantees |
|---|---|---|
| `packaging/install.sh` | A fresh host/prefix or a controlled upgrade within the marked layout introduced in 0.2.0 | Host provisioning, verified JDK, safe marked layout, immutable activation, config preservation and local storage health gate |
| `packaging/deploy-local.sh` | Repeated 0.2.0+ deployments from a local checkout to a Debian/WSL test host | Clean `verify`, build identity, DB backup, atomic activation, health gate and automatic rollback |

`install.sh` restores the previous release/unit and restarts a previously active
service when a later install step fails, but it does not create or restore a DB
backup. For production upgrades, take and verify a backup before running it and
retain the previous release directory.
Neither path merges operator configuration or reverses external effects. Use
packaging scripts/templates from the selected application release; an arbitrary
checkout's installer is not necessarily compatible with an older JAR.

## Prerequisites

| Dependency | Required boundary |
|---|---|
| Host | GNU/Linux with Bash, GNU coreutils/findutils/tar and systemd as PID 1. Native Windows/macOS and containers without systemd are not supported by these scripts. WSL needs systemd enabled. |
| Privileges/tools | Root or `sudo` for activation; `curl`, CA trust, `flock` (`util-linux`), account tools (`passwd`), `systemctl`, `systemd-run`, `journalctl`. The installer checks commands; it does not install OS packages. |
| Java | Java 21 is the qualified runtime/build baseline. The installer accepts system Java 21+ or a verified archive; accepting a higher major does not qualify it. Default archives target Linux x86-64/AArch64; other CPU/libc combinations need validation. |
| Application | Bootable application JAR, trusted SHA-256, matched packaging files and release notes. Ordinary reactor module/library JARs are not runnable distributions. |
| Filesystem | Local storage with reliable SQLite locking and same-filesystem atomic rename for file hand-off. Budget both DBs/WAL, source snapshots/workspaces, exports, backups, JDK and retained releases. Do not put live DBs on SMB/NFS. |
| Optional SMB | DNS/routing and outbound TCP to configured SMB ports (normally 445), an existing share, credentials and ACLs. Required encryption needs an effectively encrypted SMB3 session. The application does not provision shares/ACLs. |
| Build host only | Git, full JDK 21 and Maven wrapper, with repositories or a prepared cache. A target running a prepared JAR needs no Maven, Git, separate Spring/Tomcat installation or library publication setup. |

SQLite is embedded through sqlite-jdbc; no DB server or system `sqlite3` command
is needed for normal operation. Its native library needs a writable, loadable
temporary location: a `noexec` mount or unsupported architecture/libc can prevent
startup. See [native loader behavior](https://github.com/xerial/sqlite-jdbc/blob/3.53.2.0/src/main/java/org/sqlite/SQLiteJDBCLoader.java).
Document parsing needs no Office/LibreOffice installation for the supported
HTML/PDF/DOCX/XLSX contract; [processing](../dev/processing.md) owns format limits.

The application JAR carries its selected runtime libraries, including the
project's shared modules. Startup does not download them from Maven repositories.
Use the complete matched JAR; do not replace individual nested libraries to
resolve a conflict. Check the selected bundle's dependency/security evidence and
[known issues](../KNOWN-ISSUES.md) before production promotion. Open advisory
disposition (`SRC-3`) is separate from successful installation or health checks.

Do not install over a source checkout. `/opt/ioc-extractor` is the recommended
production prefix; `/srv/ioc-extractor` is the local-deployment default.
The prefix must be a normalized dedicated directory: system roots, protected
system subtrees, symlink traversal and non-empty unrelated directories are
rejected. The daemon account must never resolve to UID 0.
The fixed service name is `ioc-extractor`: a second prefix does not create a
second independent service. Run one daemon per storage/intake namespace. Keep
Java and application state outside `/home`, which `ProtectHome` hides.

## Fresh installation

For a released installation, obtain the selected JAR, checksum and matching
packaging files from the trusted release and review its upgrade notes. Calculating
a new checksum for an untrusted download does not verify its origin.

Developers building their own candidate run the reactor as an ordinary user:

```bash
./mvnw -B -ntp -T 1C clean verify
APP_VERSION="$(./mvnw -q help:evaluate -Dexpression=project.version -DforceStdout)"
APP_JAR="bootstrap/ioc-app/target/ioc-app-${APP_VERSION}.jar"
sha256sum "${APP_JAR}" > "${APP_JAR}.sha256"
```

Install with an explicit jar and checksum:

```bash
sudo packaging/install.sh \
  --prefix /opt/ioc-extractor \
  --jar "${APP_JAR}" \
  --checksum "${APP_JAR}.sha256" \
  --no-start
```

The installer creates a dedicated `ioc` account, installs the pinned Temurin 21
archive unless `--system-java` is selected, verifies it against the pinned
SHA-256 before staged extraction, deploys an immutable release, installs the
launcher and systemd unit. This example leaves it stopped for review. Without
`--no-start`, installation starts it immediately and checks three local storage
health components.

The fresh production configuration enables canonical record validity at `12h`.
Review the [canonical record lifecycle guide](canonical-record-lifecycle.md)
before changing that policy. This fresh preset is not silently merged into an
existing operator configuration during upgrade.

For an offline host, transfer a trusted Temurin 21 tarball with the application:

```bash
sudo packaging/install.sh \
  --jar /tmp/ioc-extractor.jar \
  --checksum /tmp/ioc-extractor.jar.sha256 \
  --jdk-tarball /tmp/temurin-21.tar.gz \
  --jdk-sha256 "REPLACE_WITH_TRUSTED_SHA256" \
  --no-start
```

A custom `--jdk-url` likewise requires `--jdk-sha256` and must use HTTPS. The
default architecture-specific URL and digest are pinned in the release script;
they do not follow a mutable `latest` endpoint.

`--no-start` still activates `current`, writes the unit and enables the service:
it is not a dry run and does not prevent startup on a later boot. An upgraded
previously active service is also left stopped. Run
`packaging/install.sh --help` for all installer options.

## Installed layout

```text
<prefix>/
├── current -> releases/<release-id>
├── releases/<release-id>/ioc-app.jar
├── jdk/                 # unless using system Java
├── backups/
├── bin/
│   ├── ioc
│   └── ioc-config
├── etc/application.yml
├── etc/ioc-extractor.env
├── etc/ioc-extractor.installation
├── var/db/
├── var/export/
├── var/inbox/  var/processing/  var/done/  var/failed/
├── var/import/inbox/  var/import/processing/  var/import/snapshots/
├── var/import/staging/  var/import/terminal/  var/import/quarantine/
├── var/ledger/ var/logs/
└── dataframe/
```

`releases/` is immutable. Operator-owned state lives under `etc/`, `var/` and
`dataframe/`; it is not part of a release directory. The root-owned installation
marker binds the exact prefix, service name and service user; do not edit or copy
it to another directory.

All managed-import directories are service-owned `0750` state. Do not place an
operator drop location inside `processing`, `snapshots`, `staging`, `terminal`
or `quarantine`; only a configured `inbox` or a dedicated SMB path is a producer
boundary.

The unit permits writes only under `var/` and `dataframe/`, uses a private `/tmp`
and sets resource limits. Custom external paths need both service-account
permissions and a reviewed systemd writable-path drop-in. A shell-visible path
may be hidden/read-only in the service. Tune heap and unit limits together using
[bulk resource sizing](bulk-resource-sizing.md).

## Configure and validate

For a million-input workload, declare the host, JVM, workspace and execution
envelope using [bulk resource sizing](bulk-resource-sizing.md). The packaged
small-service defaults are not a million-input capacity guarantee.

Prepare a separate YAML candidate using the
[configuration reference](configuration.md). Put secrets in
`<prefix>/etc/ioc-extractor.env`, not in YAML. Do not edit the live file in
place: validation and replacement must be one controlled operation.
Packaged defaults are overridden by external YAML, then environment, JVM system
properties and CLI. Unknown `ioc.*` keys fail on every channel; list overrides
replace the complete list. Reserve `IOC_` for application properties.
EnvironmentFile syntax is not shell syntax: do not `source` it. Secret/JVM
environment changes are operator-managed, outside the YAML helper's rollback.

```bash
sudo /opt/ioc-extractor/bin/ioc-config check ./application.candidate.yml
sudo /opt/ioc-extractor/bin/ioc-config apply ./application.candidate.yml
sudo systemctl status ioc-extractor --no-pager
sudo /opt/ioc-extractor/bin/ioc --version
sudo /opt/ioc-extractor/bin/ioc health
sudo journalctl -u ioc-extractor -n 100 --no-pager
```

On a fresh `--no-start` installation, `check` then `systemctl start` is sufficient
if the installed YAML needs no change. `apply` starts/restarts even a stopped
service: it is an activation operation.

The helper stages service-readable bytes and validates YAML syntax, unknown
keys, typed binding, conversion, semantic invariants and registry references in
a configuration-only Spring context. It uses the installed service environment
and JVM overrides but does not open databases, initialize transports or compose
the runtime graph. `apply` then atomically replaces the installed YAML and waits
for health; a startup failure restores the previous file. Direct restarts run
the same validation in systemd `ExecCondition`; exit `78` skips activation
without entering a deterministic restart loop.
Success does not prove DB/schema compatibility, path access, native-library
loading or SMB authentication. YAML rollback restores the file, not runtime
state already changed by a briefly started process. Use a coordinated recovery
point for destructive lifecycle/schema changes.

The daemon reads `JAVA_OPTS`; installed `bin/ioc` instead starts a separate
oneshot process with fixed JVM options and does not forward `JAVA_OPTS`. It reads
the service EnvironmentFile, but is not the daemon. Avoid concurrent mutating
CLI work in a live daemon's namespace. A daemon-only `--server.port` unit
override is not inherited by that CLI: use `ioc health --port PORT`.

### Activation and health acceptance

| Check | Meaning |
|---|---|
| `install.sh` / `deploy-local.sh` | Service active and `jdbcStorage`, `dataframeStorage`, `artifactStorage` individually `UP`; optional remote health is not a promotion gate. |
| `ioc-config apply` | Service active and aggregate `/actuator/health` reports `UP`; another component's failure can reject a valid YAML candidate. |
| `ioc health` | Queries the daemon; exit `0` only for `UP`, `1` for another reported status, `2` for unreachable/malformed response. Does not start or repair it. |

The health endpoint is loopback-only by default. A healthy local service does
not imply that optional remote SMB endpoints have already authenticated: sync
components may remain `UNKNOWN` until their first operation.
Before admitting production input, verify release/commit, actual port, components
and logs. Submit a controlled representative document and verify its terminal
outcome plus DB/projection/export behavior. Qualify SMB and managed import
separately when enabled; local storage health is insufficient.

## Transition from 0.1.0 to 0.2.0

Version 0.2.0 does **not** support an in-place upgrade of a 0.1.0 installation.
The old release used a single `lib/ioc-app-0.1.0.jar` layout, CSV-centric state
and a configuration contract that is not accepted as the current immutable
release/SQLite layout. `--force` does not bypass this boundary, and generated
0.1.0 CSV artifacts are not a supported import format for the 0.2.0 canonical
database.

Use a filesystem-side-by-side transition. Both prefixes remain on disk, but
only one instance of the shared `ioc-extractor.service` runs at a time:

1. Stop new input and optional synchronization.
2. Stop 0.1.0 and take a verified external backup of its complete prefix,
   configuration and systemd unit.
3. Leave the old prefix unchanged as the rollback point.
4. Install 0.2.0 into a new, empty prefix with `--no-start`.
5. Configure 0.2.0 from its supplied template. Translate only reviewed
   site-specific values; do not copy the old YAML or environment file wholesale.
6. Start 0.2.0, then copy the reviewed original source documents into its inbox
   to build the new SQLite truth. Keep the old source evidence untouched.
7. Validate version, health, logs, canonical row counts and generated artifacts
   before accepting new input.
8. Retain the old prefix and unit backup for the entire rollback window.

Example cutover from the historical default prefix:

```bash
OLD_PREFIX=/opt/ioc-extractor
NEW_PREFIX=/opt/ioc-extractor-0.2
OLD_UNIT_BACKUP=/root/ioc-extractor-v0.1.0.service

sudo systemctl stop ioc-extractor
sudo install -o root -g root -m 0600 \
  /etc/systemd/system/ioc-extractor.service "${OLD_UNIT_BACKUP}"
sudo tar -C "$(dirname "${OLD_PREFIX}")" -cpf /root/ioc-extractor-v0.1.0-prefix.tar \
  "$(basename "${OLD_PREFIX}")"

sudo packaging/install.sh \
  --prefix "${NEW_PREFIX}" \
  --jar /tmp/ioc-extractor-0.2.0.jar \
  --checksum /tmp/ioc-extractor-0.2.0.jar.sha256 \
  --release-id v0.2.0 \
  --no-start
```

Review `${NEW_PREFIX}/etc/application.yml` and its environment file before
starting the service. Re-ingest trusted original documents; do not seed the new
database from generated 0.1.0 CSV projections.

Rollback across the 0.1.0/0.2.0 boundary restores the untouched 0.1.0 prefix and
saved unit. It never points 0.1.0 at the 0.2.0 SQLite databases:

```bash
sudo systemctl stop ioc-extractor
sudo install -o root -g root -m 0644 "${OLD_UNIT_BACKUP}" \
  /etc/systemd/system/ioc-extractor.service
sudo systemctl daemon-reload
sudo systemctl start ioc-extractor
```

Inputs accepted only after the 0.2.0 cutover are not present in the old prefix.
Preserve them and explicitly reconcile/resubmit them if rollback is required.

## Upgrade within the marked layout with `install.sh`

An accepted layout is not proof of a supported upgrade. Identify the installed
binary/commit, YAML/environment, both database states, lifecycle mode and artifact
identities; compare the selected release's upgrade/rollback contract. Startup may
migrate both schemas before intake. There is no general schema downgrade.

1. Verify the new jar and checksum on a trusted build host.
2. Pause producers, optional sync and mutating CLI work; stop the service.
3. Back up the complete prefix and unit to protected external storage: both DBs
   and sidecars, config/secrets, private intake files, exports/projections,
   helpers and matching Java/runtime selection form one recovery point.
4. Run matching `install.sh` with a new immutable `--release-id` and `--no-start`.
5. Reconcile `*.new` with release notes; check the candidate with the new binary
   and installed environment before runtime startup.
6. Start, inspect migrations/health/logs and exercise a representative operation
   before resuming input. Perform one-way feature activation separately.

```bash
sudo systemctl stop ioc-extractor
CHANGE_ID="$(date -u +%Y%m%dT%H%M%SZ)"
BACKUP_DIR="/root/ioc-extractor-${CHANGE_ID}"
sudo install -d -o root -g root -m 0700 "${BACKUP_DIR}"
sudo tar --acls --xattrs -C /opt -cpf "${BACKUP_DIR}/prefix.tar" ioc-extractor
sudo install -o root -g root -m 0600 \
  /etc/systemd/system/ioc-extractor.service "${BACKUP_DIR}/unit.service"

sudo packaging/install.sh \
  --prefix /opt/ioc-extractor \
  --jar /tmp/ioc-extractor-new.jar \
  --checksum /tmp/ioc-extractor-new.jar.sha256 \
  --release-id v0.3.0 \
  --no-start
```

If the host uses a non-default actuator port, also pass `--server-port PORT`.
The installer renders it as a command-line `--server.port` override and uses the
same value for its health gate. Health timing is tunable through
`--health-attempts` and `--health-interval`.
Keep the actual port consistent with YAML/helper/probe; changing it only in YAML
can leave packaging's probe pointed at the old/default port.

The installer preserves an existing operator file. When a packaged template has
changed it writes `application.yml.new` or `ioc-extractor.env.new` beside it.
Compare the files; merge new supported properties into the operator copy; keep
site-specific paths, policies and secrets; then remove the `.new` file after
successful validation.

If an older unreconciled `.new` differs from the incoming template, deployment
stops with `PACKAGING.CONFIG_CANDIDATE_CONFLICT` before overwriting it. The
report names all three files, gives timestamps and SHA-256 digests, and prints
copy-ready `diff` and archive commands without printing their contents. Run the
suggested diff only in a trusted terminal because the diff itself may expose
secrets. Reconcile the old candidate, then archive or remove it before retrying.

For a TTL-capable upgrade, keep lifecycle mode disabled for the first
compatibility start. The later fixed-validity cutover is destructive to legacy
active membership and follows the separate
[canonical lifecycle procedure](canonical-record-lifecycle.md#upgrade-an-existing-installation).
That compatibility start may log `CONFIG.LEGACY_ARTIFACT_IDENTITY` when the
operator file still contains the exact built-in v0.2.0 identity list. This is an
expected rollback-overlap warning, not permission to omit versioned identity
fields from custom entries. Reconcile the explicit definitions only after the
v0.2.0 rollback point is retired.
The same additive dataframe migration installs the export-slot registry. Its
first active export seeds current external IDs without renumbering survivors;
rollback therefore still requires the matching binary/configuration and both
database backups rather than a partial schema downgrade.

```bash
sudo diff -u /opt/ioc-extractor/etc/application.yml \
  /opt/ioc-extractor/etc/application.yml.new
```

Do not use `--force` merely to silence this reconciliation: it overwrites the
operator file with the packaged template.

Direct v0.2.0 upgrade accepts the exact four legacy built-in identity entries,
not arbitrary missing versioned keys. The temporary SMB `encrypt` alias must not
be supplied together with `encryption`. Keep key choices compatible with the
retained rollback binary until that window closes. Removed `read-timeout` is
rejected. See [configuration](configuration.md) and the selected release notes;
do not replace operator YAML wholesale with the fresh template.

## Local checkout deployment

Run this command as an ordinary user:

```bash
./packaging/deploy-local.sh --prefix /srv/ioc-extractor
```

It rejects a dirty checkout unless `--allow-dirty` is explicit, runs the full
Maven gate, verifies that the build did not change the checkout, creates a
release identified by commit and build time, then uses that new jar to validate
the effective installed configuration as the service account. A validation
failure leaves the running service and SQLite databases untouched. Only after
that gate does deployment back up both databases and the previous systemd unit,
atomically switch `current`, start the service and run a local health gate. On
failure it restores the previous symlink, unit and DB backup, including the
database POSIX ACLs and extended attributes. `--port PORT` becomes the daemon's
high-precedence `--server.port` value, not merely the probe address.

Known pre-activation limitation (`OPS-13`): rejection leaves the process/current
link/DBs in place, but the unit and CLI helpers may already have been refreshed.
An explicit failure exit bypasses the unit-restoration error trap. Before the
next restart, inspect the unit against `backups/<failed-release-id>-unit.service`
and restore matching unit/helpers if needed; reload systemd after unit restore.
A rejected deployment does not promise that every host file is unchanged.
Track the fix in [known issues](../KNOWN-ISSUES.md).

The build JVM is checked before Maven starts. If the host default is older than
21, select a JDK 21 installation explicitly for the deployment shell:

```bash
export JAVA_HOME=/path/to/jdk-21
export PATH="${JAVA_HOME}/bin:${PATH}"
make doctor-core
```

Use this path for a test stand, not as a substitute for a reviewed production
release process. It bootstraps a clean prefix and upgrades only the current
marked release layout; it is not a 0.1.0 migration command.

Rollback is deliberately bounded to the application symlink, its version-matched
systemd unit and two SQLite databases, including their POSIX ACLs and extended
attributes. It cannot reverse input files already moved by a briefly running new
daemon, generated CSV/export files or completed remote writes. Pause input and
optional synchronization before a rollback-sensitive migration.

## Manual application rollback

If an `install.sh` upgrade fails but the data schema is known to be compatible:

1. stop the service;
2. point a temporary symlink at the previous immutable release;
3. atomically replace `current`;
4. restore the matching DB backup if the failed release may have written data;
5. restore the matching systemd unit and reload systemd;
6. restore matching config/environment and required service-owned files, then
   start and validate the service.

The symlink example below assumes compatible DBs and unchanged configuration;
otherwise keep the service stopped until coordinated restore is complete.
The `*-unit.service` sidecar is created by local deployment. For `install.sh`,
use the unit saved in the external backup instead; its temporary recovery copy
does not survive installer exit. Also check restored helpers and Java selection.

```bash
sudo systemctl stop ioc-extractor
cd /opt/ioc-extractor
sudo ln -s "releases/<previous-release-id>" .current.rollback
sudo mv -Tf .current.rollback current
sudo install -o root -g root -m 0644 \
  "backups/<failed-release-id>-unit.service" \
  /etc/systemd/system/ioc-extractor.service
sudo systemctl daemon-reload
sudo systemctl start ioc-extractor
sudo ./bin/ioc health
```

Never combine an old application release with an arbitrary newer database or
unit. Application, unit, configuration and database backup form one rollback
point. The unit sidecar uses the release ID of the deployment being rolled back
because it contains that deployment's previous unit.

## Restore databases

1. stop the service;
2. preserve the failed `var/db` directory for investigation;
3. extract the complete backed-up DB directory with sidecars, ACLs and xattrs;
4. verify matching ownership, ACLs and restrictive permissions;
5. start the service and check health/logs before re-enabling input.

```bash
sudo systemctl stop ioc-extractor
sudo mv /opt/ioc-extractor/var/db /opt/ioc-extractor/var/db.failed
sudo tar --acls --xattrs -C /opt/ioc-extractor/var -xf \
  "/opt/ioc-extractor/backups/<failed-release-id>-db.tar"
sudo systemctl start ioc-extractor
```

This archive name belongs to local deployment. The manual upgrade example backs
up the whole prefix in `prefix.tar`; restore its coordinated state and saved
unit instead. Do not mix archive layouts or restore just one DB. Restore to an
empty destination while stopped, preserve the failed state, and validate the
restored ownership/ACLs rather than applying a blanket permission reset.

## Diagnose a failed deployment

Inspect these read-only observations first; substitute the actual port:

```bash
sudo systemctl status ioc-extractor --no-pager
sudo journalctl -u ioc-extractor -n 100 --no-pager
sudo /opt/ioc-extractor/bin/ioc --version
sudo /opt/ioc-extractor/bin/ioc health --port 8081 --json
curl --noproxy '*' --silent --show-error --max-time 5 \
  http://127.0.0.1:8081/actuator/health
```

| Symptom | Check and response |
|---|---|
| Unsafe prefix, mismatched marker or UID 0 rejected | Use a dedicated prefix outside the checkout/protected trees and the original non-root account. Do not edit the marker or bypass the 0.1.0 boundary. |
| Checksum, archive or release-ID collision | Re-obtain trusted matching files; an existing release ID accepts identical bytes only. Custom JDK archives need a SHA-256; system Java and archive options are mutually exclusive. |
| `UnsupportedClassVersionError`, missing main class or `NoSuchMethodError` | Check the actual service Java and bootable JAR. Do not use an ordinary module JAR or mix dependency generations; restore the matched bundle and runtime. |
| `PACKAGING.CONFIG_CANDIDATE_CONFLICT` | Reconcile/archive the older `.new` before retrying. Treat diffs as sensitive; do not publish secrets or use `--force` as a merge. |
| `CONFIG.*`, exit `78`, skipped ExecCondition | Read the reported path/category; inspect YAML, environment and system/CLI overrides, including removed keys and whole-list replacement. Run `ioc-config check`, correct the candidate, then activate deliberately. |
| Service active but health unreachable | Confirm daemon mode, actual port and startup logs. A unit CLI port override wins over YAML. Use explicit CLI `--port`; bypass HTTP proxies for loopback curl. |
| Permission/read-only/path failure | Check service UID/group, directory traversal, `ReadWritePaths`, `ProtectHome` and private `/tmp`. Keep DB/staging local and within writable namespaces. Do not solve it by running as root or broadening permissions globally. |
| SQLite native-library load failure | Check CPU/libc and temp mount restrictions. If needed, provision a service-owned loadable directory under `var/` and select it with the driver's `org.sqlite.tmpdir` JVM property; validate in the actual service sandbox. |
| OOM, termination or restart limit | Check journal/kernel/cgroup evidence and JVM/unit limits together; reduce concurrency/input budgets or size the host. `reset-failed` alone does not fix the cause. |
| Schema/identity/lifecycle incompatibility | Stop before further writes; follow the selected release's migration or coordinated restore procedure. Never hand-edit version metadata or point an older binary at newer DBs. |
| Local storage gate passed, SMB failed | Verify server/port/credentials/ACLs and effective encryption separately. A local promotion is not remote qualification. See [remote storage](remote-storage-sync.md). |
| Import health/status fails on a pending head | Known `OPS-10` can fail nullable-deadline status reads; it is not proof of failed canonical commit. Follow [managed import](dataframe-import.md); do not patch ledger SQL. |
| Rejected local deployment, unexpected unit/helpers | Follow the `OPS-13` preflight limitation above before restarting. Preserve the failed release ID and backup evidence. |

Known fetch identity/landing limits (`OPS-11`/`OPS-12`) require disjoint remote
paths/final filenames and coordinated inbox writers until fixed; see the remote
storage guide. Record unavailable capabilities explicitly during acceptance.

## Uninstall

The safe default removes the service but keeps the account and all state:

```bash
sudo packaging/uninstall.sh --prefix /opt/ioc-extractor
```

`--purge` irreversibly removes the prefix, configuration, databases, artifacts,
JDK and service account. It requires a valid installation marker and refuses UID
0. A pre-marker **0.2 release layout** must first be adopted by running the
current installer once. A 0.1.0 single-directory installation is intentionally
not adopted; preserve it for rollback or use its matching uninstaller. Take an
external backup and verify the exact prefix before purge.

## Post-deployment checklist

- `ioc --version` matches the intended release and commit.
- `systemctl is-active ioc-extractor` returns `active`.
- `ioc health` has no unexplained `DOWN` component.
- startup logs contain no `CONFIG.*` failure or unexpected override.
- `etc/application.yml` and the environment file have restrictive ownership.
- DB backup and restore locations are outside retention targets.
- inbox, processing, done and failed directories are distinct and writable only
  by the intended operator/service accounts.
- remote sync, when enabled, passes the checklist in the
  [remote storage guide](remote-storage-sync.md).

## Sources and maintenance

The [packaging reference](../../packaging/README.md),
[installer](../../packaging/install.sh),
[local activation](../../packaging/deploy-local-root.sh),
[unit](../../packaging/templates/ioc-extractor.service),
[CLI launcher](../../packaging/templates/ioc) and
[YAML helper](../../packaging/templates/ioc-config) own executable behavior.
Read the selected release notes for its compatibility boundary. Keep both
languages aligned when flags, dependencies, validation, health or recovery change.
Run `make lint-shell` and applicable configuration/runtime tests; `make docs`
checks links. Host activation, restore and optional endpoint support still need
qualification on the actual target.
