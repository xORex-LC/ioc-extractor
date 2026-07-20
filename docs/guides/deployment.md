# Deployment, upgrade and rollback

This guide describes host installation and lifecycle operations for an
ioc-extractor daemon. Debian 11 and 12 are the tested installer baselines. Other
systemd distributions are best effort and require operator validation.

## Choose a deployment path

| Path | Use it for | Guarantees |
|---|---|---|
| `packaging/install.sh` | A new host or a simple controlled upgrade from a prepared jar | Host provisioning, JDK, account, layout, immutable release activation and config preservation |
| `packaging/deploy-local.sh` | Repeated deployments from a local checkout to a Debian/WSL test host | Clean `verify`, build identity, DB backup, atomic activation, health gate and automatic rollback |

`install.sh` does not provide the automatic backup-and-health rollback transaction
of `deploy-local.sh`. For production upgrades, take and verify a backup before
running the installer and retain the previous release directory.

## Prerequisites

- root or `sudo` for host activation;
- systemd for service management;
- a regular bootable ioc-extractor jar;
- its SHA-256 checksum for release installation;
- JDK 21, or permission to install the bundled Temurin archive;
- local storage for SQLite databases and ingestion directories.

Do not install over a source checkout. `/opt/ioc-extractor` is the recommended
production prefix; `/srv/ioc-extractor` is the local-deployment default.

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

The installer creates a dedicated `ioc` account, installs JDK 21 unless
`--system-java` is selected, deploys an immutable release, installs the launcher
and systemd unit, then starts the service.

For an offline host, transfer a trusted Temurin 21 tarball with the application:

```bash
sudo packaging/install.sh \
  --jar /tmp/ioc-extractor.jar \
  --checksum /tmp/ioc-extractor.jar.sha256 \
  --jdk-tarball /tmp/temurin-21.tar.gz
```

Use `--no-start` when configuration must be reviewed before first startup. Run
`packaging/install.sh --help` for all installer options.

## Installed layout

```text
<prefix>/
├── current -> releases/<release-id>
├── releases/<release-id>/ioc-app.jar
├── bin/ioc
├── etc/application.yml
├── etc/ioc-extractor.env
├── var/db/
├── var/export/
├── var/inbox/  var/processing/  var/done/  var/failed/
├── var/ledger/ var/logs/
└── dataframe/
```

`releases/` is immutable. Operator-owned state lives under `etc/`, `var/` and
`dataframe/`; it is not part of a release directory.

## Configure and validate

Edit `<prefix>/etc/application.yml` using the
[configuration reference](configuration.md). Put secrets in
`<prefix>/etc/ioc-extractor.env`, not in YAML.

```bash
sudo systemctl restart ioc-extractor
sudo systemctl status ioc-extractor --no-pager
sudo -u ioc /opt/ioc-extractor/bin/ioc --version
sudo -u ioc /opt/ioc-extractor/bin/ioc health
sudo journalctl -u ioc-extractor -n 100 --no-pager
```

The health endpoint is loopback-only by default. A healthy local service does
not imply that optional remote SMB endpoints have already authenticated: sync
components may remain `UNKNOWN` until their first operation.

## Upgrade with `install.sh`

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
  --release-id v0.1.1
```

The installer preserves an existing operator file. When a packaged template has
changed it writes `application.yml.new` or `ioc-extractor.env.new` beside it.
Compare the files; merge new supported properties into the operator copy; keep
site-specific paths, policies and secrets; then remove the `.new` file after
successful validation.

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
Maven gate, creates a release identified by commit and build time, backs up both
SQLite databases, atomically switches `current`, starts the service and runs a
local health gate. On failure it restores the previous symlink and DB backup.

Use this path for a test stand, not as a substitute for a reviewed production
release process.

## Manual application rollback

If an `install.sh` upgrade fails but the data schema is known to be compatible:

1. stop the service;
2. point a temporary symlink at the previous immutable release;
3. atomically replace `current`;
4. restore the matching DB backup if the failed release may have written data;
5. start and validate the service.

```bash
sudo systemctl stop ioc-extractor
cd /opt/ioc-extractor
sudo ln -s releases/<previous-release-id> .current.rollback
sudo mv -Tf .current.rollback current
sudo systemctl start ioc-extractor
sudo ./bin/ioc health
```

Never combine an old application release with an arbitrary newer database.
Application, configuration and database backup form one rollback point.

## Restore databases

1. stop the service;
2. preserve the failed `var/db` directory for investigation;
3. copy the complete backed-up DB directory, including any sidecars;
4. restore ownership and restrictive permissions;
5. start the service and check health/logs before re-enabling input.

```bash
sudo systemctl stop ioc-extractor
sudo mv /opt/ioc-extractor/var/db /opt/ioc-extractor/var/db.failed
sudo cp -a /opt/ioc-extractor/backups/<backup-id>/db /opt/ioc-extractor/var/db
sudo chown -R ioc:ioc /opt/ioc-extractor/var/db
sudo systemctl start ioc-extractor
```

## Uninstall

The safe default removes the service but keeps the account and all state:

```bash
sudo packaging/uninstall.sh --prefix /opt/ioc-extractor
```

`--purge` irreversibly removes the prefix, configuration, databases, artifacts,
JDK and service account. Take an external backup and verify the exact prefix
before using it.

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
