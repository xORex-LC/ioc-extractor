# packaging

## Purpose

Host installation and deployment boundary for ioc-extractor. This directory
contains shell entry points and rendered host templates; it contains no Java
runtime logic.

**Boundary rule:** scripts may build, install and operate the application but
must not duplicate extraction, storage or synchronization behavior. Operator
procedures live in `docs/guides/` and are linked below.

Debian 11 and 12 are the tested installer baselines. Other systemd hosts are
best effort and must be validated by the operator.

## Contents

| File | Responsibility |
|---|---|
| `install.sh` | Provision a host, JDK 21, service account, immutable release, configuration, launcher and systemd unit. |
| `uninstall.sh` | Remove the unit safely; retain data by default or purge only when explicit. |
| `deploy-local.sh` | Ordinary-user entry point: clean verify/build, then privileged local activation. |
| `deploy-local-root.sh` | Root-only activation transaction with DB backup, health gate and rollback. Do not call directly. |
| `install-layout.sh` | Shared safe-prefix, installation-marker and service-account contract. |
| `prepare-release-artifacts.sh` | Validate tag/Maven/build identity and produce checksummed public jar assets. |
| `publish-release-draft.sh` | Create or repair the GitHub draft release for prepared immutable assets. |
| `templates/application.yml` | Full production daemon override; safe baseline plus disabled optional integrations. |
| `templates/ioc-extractor.env` | JVM options and secret environment placeholders. |
| `templates/ioc-extractor.service` | Hardened systemd unit template. |
| `templates/ioc` | Installed host launcher for CLI and health operations. |
| `tests/` | Temporary-directory packaging contract checks; no host provisioning. |

## Operator documentation

- [Deployment, upgrade and rollback](../docs/guides/deployment.md)
- [Complete configuration reference](../docs/guides/configuration.md)
- [Daemon operations](../docs/guides/daemon-operations.md)
- [Remote storage synchronization](../docs/guides/remote-storage-sync.md)

The guides are canonical procedures. This README documents only the scripts,
templates and packaging invariants beside their files.

## Installed layout

The installer creates one self-contained prefix:

```text
<prefix>/
├── jdk/                         # private Temurin 21 unless --system-java
├── releases/<release-id>/       # immutable application releases
│   └── ioc-app.jar
├── current -> releases/<id>     # atomically switched active release
├── bin/ioc                      # host launcher
├── etc/
│   ├── application.yml          # operator-owned override
│   └── ioc-extractor.env        # JVM settings and secrets
├── backups/                     # local deployment backups
├── var/
│   ├── db/                      # canonical dataframe + service ledgers
│   ├── export/                  # immutable slices and delivery state
│   ├── inbox/ processing/ done/ failed/
│   └── ledger/ logs/
└── dataframe/                   # generated mutable CSV projections
```

Release files are root-owned and immutable. The service account owns writable
runtime state. `etc/`, `var/` and `dataframe/` stay outside release directories
so activation never replaces operator data.

## `install.sh`

```text
sudo ./packaging/install.sh [--prefix DIR] [--jar PATH] [--checksum PATH]
    [--release-id ID] [--user NAME]
    [--jdk-tarball PATH | --jdk-url URL] [--jdk-sha256 HEX]
    [--system-java] [--server-port PORT]
    [--health-attempts N] [--health-interval SECONDS]
    [--no-start] [--force]
```

Key contracts:

- must run as root and accepts only a normalized dedicated prefix outside
  protected system trees;
- refuses source checkouts and non-empty unrelated directories without a bypass;
- writes a root-owned installation marker binding prefix, service and non-root
  service account; destructive lifecycle operations require that marker;
- accepts exactly one regular application jar and verifies an optional checksum;
- requires Java 21 or installs the architecture-specific pinned Temurin 21
  archive after SHA-256 verification and staging extraction;
- creates a unique immutable release and atomically replaces `current`;
- preserves existing operator config and writes changed templates as `*.new`;
- renders the systemd unit with exact paths and optional `--server-port`, starts
  it unless `--no-start`, and requires storage health to become `UP`;
- re-running with the same release ID is allowed only when bytes are identical.

`--force` permits overwriting operator configuration only. It never bypasses
prefix, source-tree, marker, user or checksum safety. Reconcile `*.new` files as
described in the deployment guide.

The installer does not provide the database backup and automatic rollback
transaction of `deploy-local.sh`.

## `deploy-local.sh`

```text
./packaging/deploy-local.sh [--prefix DIR] [--port PORT] [--allow-dirty]
    [--release-retention N] [--backup-retention N]
    [--health-attempts N] [--health-interval SECONDS]
```

It runs as an ordinary user, serializes deployments with a lock and always runs
the complete Maven `clean verify` gate. A dirty tree is rejected unless
`--allow-dirty` is explicit and receives a visible dirty release identity. The
tree is compared again after the build so generated/concurrent changes cannot be
published under the wrong commit identity. `--port` is rendered into the daemon
unit as the high-precedence `--server.port` override and is also used by the
health gate.

The privileged phase:

1. verifies the exact jar checksum and release metadata;
2. stops the active service;
3. backs up both SQLite databases as one recovery point;
4. installs a new immutable release and atomically switches `current`;
5. starts the service and checks local actuator health;
6. restores the previous release and database backup if the gate fails;
7. retains only the configured number of releases and backups.

Remote sync health is deliberately not a deployment gate: an unavailable
optional SMB server must not roll back a locally healthy application release.
The automatic rollback covers the active application symlink and both SQLite
databases. It cannot reverse files already moved by ingestion, generated
projections/export slices or completed remote side effects; pause input and
optional synchronization when testing a rollback-sensitive migration.

## `uninstall.sh`

```text
sudo ./packaging/uninstall.sh [--prefix DIR] [--user NAME] [--purge]
```

Without `--purge`, the script stops/disables the service and removes its unit but
keeps the prefix, account, config and all data. `--purge` permanently deletes the
marker-validated prefix and non-root service account. A legacy pre-marker install
must first be adopted by one safe `install.sh` run before purge is available.

## Automated packaging gate

CI runs ShellCheck, shell syntax checks, installation-layout contracts and a
rendered `systemd-analyze verify`. These tests pin dangerous-target rejection,
marker/user semantics and explicit server-port rendering. End-to-end host
activation and rollback still belong on a disposable systemd test stand.

## Release helper contracts

`prepare-release-artifacts.sh` accepts only `vX.Y.Z` or `vX.Y.Z-rc.N`, rejects a
SNAPSHOT or tag/version mismatch, verifies embedded build version/commit/time and
the lightweight `--version` output, then creates a jar and SHA-256 sidecar without
overwriting existing assets.

`publish-release-draft.sh` validates the annotated tag, exact commit, release
notes and asset checksums before creating or repairing a GitHub draft. It never
publishes the release automatically.

The GitHub release workflow builds the reactor once, rejects source-tree changes
produced by the build and passes the same prepared assets to the draft step.

## Configuration contract

The embedded classpath configuration supplies application defaults. The
installed `etc/application.yml` is an external override and remains
operator-owned across releases. Effective application precedence is:

```text
packaged defaults < external YAML < environment < system properties < CLI
```

Unknown `ioc.*` keys from any channel fail startup. The full template deliberately
shows every supported section and complete list element shapes. Remote sync stays
disabled and secrets remain environment placeholders.

On upgrade, a changed packaged template is written beside the existing file as
`application.yml.new` or `ioc-extractor.env.new`; it is never silently merged.

## Systemd contract

The rendered service:

- runs as the dedicated non-login account;
- sets the prefix as `WorkingDirectory`;
- forces daemon mode and the servlet health surface on the command line;
- loads `etc/application.yml` and `etc/ioc-extractor.env`;
- binds actuator to loopback by default;
- permits writes only to declared state directories;
- applies filesystem, privilege, capability, syscall and resource hardening;
- uses a bounded JVM heap while leaving native-memory headroom;
- restarts on failure with start-rate limiting.

SMB endpoint hostnames are operator-owned, so the generic unit cannot apply a
fixed `IPAddressAllow` network allowlist. Network segmentation and egress policy
belong to the deployment environment.

## SMB permission boundary

Fetch source directories require read/list only; the application never deletes
remote source files. Publish targets require create, write, rename **and delete**
permission for application-owned temporary/incomplete directories and retry
cleanup. Consumers should receive read-only access to completed targets.

## Data ownership

- `ioc-dataframe.db` is canonical business truth and provenance.
- `ioc-service.db` contains ingestion, export and synchronization ledgers.
- `dataframe/*.csv` files are generated mutable projections.
- `var/export` contains immutable slices, manifests and completion markers.
- `var/done` and `var/failed` retain source evidence until configured retention.

Back up both databases together with the matching application/configuration
release. Procedures are in the deployment and daemon operation guides.
