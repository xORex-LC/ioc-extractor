# Deployment, upgrade and rollback

This guide describes host installation and lifecycle operations for an
ioc-extractor daemon. Debian 11 and 12 are the tested installer baselines. Other
systemd distributions are best effort and require operator validation.

## Choose a deployment path

| Path | Use it for | Guarantees |
|---|---|---|
| `packaging/install.sh` | A fresh host/prefix or a controlled upgrade within the marked layout introduced in 0.2.0 | Host provisioning, verified JDK, safe marked layout, immutable activation, config preservation and local storage health gate |
| `packaging/deploy-local.sh` | Repeated 0.2.0+ deployments from a local checkout to a Debian/WSL test host | Clean `verify`, build identity, DB backup, atomic activation, health gate and automatic rollback |

`install.sh` restores the previous release/unit and restarts a previously active
service when a later install step fails, but it does not create or restore a DB
backup. For production upgrades, take and verify a backup before running it and
retain the previous release directory.

## Prerequisites

- root or `sudo` for host activation;
- systemd for service management;
- a regular bootable ioc-extractor jar;
- its SHA-256 checksum for release installation;
- JDK 21, or permission to install the bundled Temurin archive;
- local storage for SQLite databases and ingestion directories.

Do not install over a source checkout. `/opt/ioc-extractor` is the recommended
production prefix; `/srv/ioc-extractor` is the local-deployment default.
The prefix must be a normalized dedicated directory: system roots, protected
system subtrees, symlink traversal and non-empty unrelated directories are
rejected. The daemon account must never resolve to UID 0.

## Fresh installation

Build and verify the project as an ordinary user:

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
  --checksum "${APP_JAR}.sha256"
```

The installer creates a dedicated `ioc` account, installs the pinned Temurin 21
archive unless `--system-java` is selected, verifies it against the pinned
SHA-256 before staged extraction, deploys an immutable release, installs the
launcher and systemd unit, then requires all local storage health components to
become `UP`.

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
  --jdk-sha256 <trusted-archive-sha256>
```

A custom `--jdk-url` likewise requires `--jdk-sha256` and must use HTTPS. The
default architecture-specific URL and digest are pinned in the release script;
they do not follow a mutable `latest` endpoint.

Use `--no-start` when configuration must be reviewed before first startup. Run
`packaging/install.sh --help` for all installer options.

## Installed layout

```text
<prefix>/
├── current -> releases/<release-id>
├── releases/<release-id>/ioc-app.jar
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

## Configure and validate

Prepare a separate YAML candidate using the
[configuration reference](configuration.md). Put secrets in
`<prefix>/etc/ioc-extractor.env`, not in YAML. Do not edit the live file in
place: validation and replacement must be one controlled operation.

```bash
sudo /opt/ioc-extractor/bin/ioc-config check ./application.candidate.yml
sudo /opt/ioc-extractor/bin/ioc-config apply ./application.candidate.yml
sudo systemctl status ioc-extractor --no-pager
sudo /opt/ioc-extractor/bin/ioc --version
sudo /opt/ioc-extractor/bin/ioc health
sudo journalctl -u ioc-extractor -n 100 --no-pager
```

The apply helper stages and syntax-checks the exact bytes, atomically replaces
the installed YAML and waits for health. A startup failure restores the previous
file. Direct restarts still have a systemd `ExecCondition` syntax guard; exit
`78` skips activation without entering a deterministic restart loop.

The health endpoint is loopback-only by default. A healthy local service does
not imply that optional remote SMB endpoints have already authenticated: sync
components may remain `UNKNOWN` until their first operation.

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

1. Verify the new jar and checksum on a trusted build host.
2. Stop input submission or otherwise establish an ingestion maintenance window.
3. Stop the service and back up `var/db`.
4. Run `install.sh` with a new immutable `--release-id`.
5. Reconcile any generated `*.new` configuration files.
6. Restart and validate version, health, logs and a representative operation.

```bash
sudo systemctl stop ioc-extractor
sudo install -d -m 0750 /opt/ioc-extractor/backups/manual-before-upgrade
sudo cp -a /opt/ioc-extractor/var/db \
  /opt/ioc-extractor/backups/manual-before-upgrade/

sudo packaging/install.sh \
  --prefix /opt/ioc-extractor \
  --jar /tmp/ioc-extractor-new.jar \
  --checksum /tmp/ioc-extractor-new.jar.sha256 \
  --release-id v0.3.0
```

If the host uses a non-default actuator port, also pass `--server-port PORT`.
The installer renders it as a command-line `--server.port` override and uses the
same value for its health gate. Health timing is tunable through
`--health-attempts` and `--health-interval`.

The installer preserves an existing operator file. When a packaged template has
changed it writes `application.yml.new` or `ioc-extractor.env.new` beside it.
Compare the files; merge new supported properties into the operator copy; keep
site-specific paths, policies and secrets; then remove the `.new` file after
successful validation.

For a TTL-capable upgrade, keep lifecycle mode disabled for the first
compatibility start. The later fixed-validity cutover is destructive to legacy
active membership and follows the separate
[canonical lifecycle procedure](canonical-record-lifecycle.md#upgrade-an-existing-installation).
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

## Local checkout deployment

Run this command as an ordinary user:

```bash
./packaging/deploy-local.sh --prefix /srv/ioc-extractor
```

It rejects a dirty checkout unless `--allow-dirty` is explicit, runs the full
Maven gate, verifies that the build did not change the checkout, creates a
release identified by commit and build time, backs up both SQLite databases and
the previous systemd unit, atomically switches `current`, starts the service and
runs a local health gate. On failure it restores the previous symlink, unit and
DB backup, including the database POSIX ACLs and extended attributes. `--port
PORT` becomes the daemon's high-precedence `--server.port` value, not merely the
probe address.

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
6. start and validate the service.

```bash
sudo systemctl stop ioc-extractor
cd /opt/ioc-extractor
sudo ln -s releases/<previous-release-id> .current.rollback
sudo mv -Tf .current.rollback current
sudo install -o root -g root -m 0644 \
  backups/<failed-release-id>-unit.service \
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
3. extract the complete backed-up DB directory, including any sidecars;
4. restore ownership and restrictive permissions;
5. start the service and check health/logs before re-enabling input.

```bash
sudo systemctl stop ioc-extractor
sudo mv /opt/ioc-extractor/var/db /opt/ioc-extractor/var/db.failed
sudo tar -C /opt/ioc-extractor/var -xf \
  /opt/ioc-extractor/backups/<failed-release-id>-db.tar
sudo chown -R ioc:ioc /opt/ioc-extractor/var/db
sudo systemctl start ioc-extractor
```

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
