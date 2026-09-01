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
| `templates/ioc-config` | Root-only candidate check/apply helper with atomic replacement, health gate and config rollback. |
| `tests/` | Temporary-directory packaging contract checks; no host provisioning. |

## Operator documentation

- [Deployment, upgrade and rollback](../docs/guides/deployment.md)
- [Complete configuration reference](../docs/guides/configuration.md)
- [Daemon operations](../docs/guides/daemon-operations.md)
- [Canonical record lifecycle](../docs/guides/canonical-record-lifecycle.md)
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
├── bin/
│   ├── ioc                     # isolated application CLI launcher
│   └── ioc-config              # atomic operator-YAML check/apply helper
├── etc/
│   ├── application.yml          # operator-owned override
│   └── ioc-extractor.env        # JVM settings and secrets
├── backups/                     # paired DB tar + previous systemd unit per deployment
├── var/
│   ├── db/                      # canonical dataframe + service ledgers
│   ├── export/                  # immutable slices and delivery state
│   ├── inbox/ processing/ done/ failed/
│   ├── import/                  # private managed-import work and evidence
│   │   └── inbox/ processing/ snapshots/ staging/ terminal/ quarantine/
│   └── ledger/ logs/
└── dataframe/                   # generated mutable CSV projections
```

Release files are root-owned and immutable. The service account owns writable
runtime state. `etc/`, `var/` and `dataframe/` stay outside release directories
so activation never replaces operator data.

Fresh installs and upgrades create the complete `var/import` tree as private
`0750` service-owned state. The production template is event-first wherever a
reliable doorbell exists: SMB `CHANGE_NOTIFY`, canonical-change and
slice-completed events provide latency, while 2s import, 5s local-ingest and 10s
export/sync scans remain correctness backstops. Local WatchService remains an
explicit opt-in because it has no complete-rescan backstop of its own. File
stability is 2s and export burst coalescing is 1s; zero-duration windows are
intentionally not used because they can claim a producer's in-place copy or
create redundant slices.

This marked layout was introduced in 0.2.0 and is retained by the 0.3.x line.
The 0.1.0 single-directory layout (`lib/ioc-app-0.1.0.jar`) is intentionally not
adopted in place: 0.2.0 uses a fresh side-by-side prefix and rebuilds SQLite
truth by re-ingesting reviewed original sources. The old prefix remains the
cross-version rollback point.

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
- detects a 0.1.0 single-directory installation and rejects in-place adoption
  with a pointer to the side-by-side transition procedure;
- accepts exactly one regular application jar and verifies an optional checksum;
- requires Java 21 or installs the architecture-specific pinned Temurin 21
  archive after SHA-256 verification and staging extraction;
- creates a unique immutable release and atomically replaces `current`;
- preserves existing operator config and writes changed templates as `*.new`;
- renders the systemd unit with exact paths and optional `--server-port`, starts
  it unless `--no-start`, and requires storage health to become `UP`;
- re-running with the same release ID is allowed only when bytes are identical.

`--force` permits overwriting operator configuration only. It never bypasses
prefix, source-tree, marker, user, checksum or the 0.1.0 fresh-install boundary.
Reconcile `*.new` files as described in the deployment guide.

The installer does not provide the database backup and automatic rollback
transaction of `deploy-local.sh`.

Upgrades within the marked layout introduced in 0.2.0 may reuse the prefix,
subject to the release-specific database/configuration procedure. Transition
from 0.1.0 to 0.2.0 is filesystem-side-by-side: preserve the old prefix and
unit, install into a clean prefix, configure from the 0.2.0 template and
re-ingest trusted source documents. Generated 0.1.0 CSV projections are not a
supported SQLite import path.

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
health gate. The script rejects a build JVM older than 21 before starting Maven;
select the intended JDK through `JAVA_HOME` and `PATH` when the host's default
Java is older than the service runtime.

The script bootstraps a clean prefix or upgrades an existing marked layout from
0.2.0 or later. It is not a migration command for the 0.1.0 single-directory
layout.

The privileged phase:

1. verifies the exact jar checksum and release metadata;
2. stages the new immutable release and refreshes packaged helpers/unit files;
3. runs the new jar's strict binding and semantic preflight as the service
   account, with the installed environment file and JVM overrides, before the
   active service is stopped;
4. stops the active service;
5. backs up both SQLite databases, including their POSIX ACLs and extended
   attributes, and the previous systemd unit as one recovery point;
6. atomically switches `current`, starts the service and checks local actuator
   health;
7. restores the previous release, systemd unit and database backup if the gate fails;
8. retains only the configured number of releases and backups.

Remote sync health is deliberately not a deployment gate: an unavailable
optional SMB server must not roll back a locally healthy application release.
The automatic rollback covers the active application symlink, the version-matched
systemd unit and both SQLite databases, including database POSIX ACLs and extended
attributes. Unit restoration matters when an older release does not understand a
newly introduced pre-start command. Database and unit sidecars share one release
ID and retention prunes them as a pair. Rollback cannot reverse files already
moved by ingestion, generated projections/export slices or completed remote side
effects; pause input and optional synchronization when testing a
rollback-sensitive migration.

## `uninstall.sh`

```text
sudo ./packaging/uninstall.sh [--prefix DIR] [--user NAME] [--purge]
```

Without `--purge`, the script stops/disables the service and removes its unit but
keeps the prefix, account, config and all data. `--purge` permanently deletes the
marker-validated prefix and non-root service account. A pre-marker 0.2 release
layout must first be adopted by one safe `install.sh` run before purge is
available. A 0.1.0 installation must use its matching uninstaller or remain
preserved as a rollback point.

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
disabled and secrets remain environment placeholders. For a clean prefix the
template enables canonical fixed validity at `12h`, with `30d` history and
receipt retention; `existing-records: reject` prevents an unexpected destructive
activation if the supposedly fresh dataframe DB is not empty.

On upgrade, a changed packaged template is written beside the existing file as
`application.yml.new` or `ioc-extractor.env.new`; it is never silently merged.
If an older `.new` would itself be overwritten, installation stops with
`PACKAGING.CONFIG_CANDIDATE_CONFLICT`. The report identifies the live file, old
candidate and incoming template, includes timestamps and SHA-256 digests, and
prints ready-to-run `diff` and archive commands without printing configuration
contents. Compare and reconcile the files, then archive or remove the stale
candidate before retrying; `--force` is not a reconciliation mechanism.

A breaking key rename that must survive binary rollback follows the bounded
expand/contract contract in ADR-0027. The overlap release temporarily accepts an
explicitly allowlisted legacy alias, warns without logging its value and rejects
old and new keys together. Keep the legacy key while the previous binary is an
active rollback point; after that point is retired, validate and apply a separate
candidate using the current key. Fresh templates never include compatibility
aliases.

The 0.3.0 overlap also recognizes the exact four built-in
`ioc.artifact-identity.artifacts[]` entries shipped by v0.2.0 before versioned
record and match keys existed. It emits `CONFIG.LEGACY_ARTIFACT_IDENTITY` and
maps only those unchanged shapes to the current definitions; custom or modified
entries with missing `record-key` fail validation. Keep the v0.2.0 shape only
while that binary is a rollback point, then reconcile `application.yml.new`
through `ioc-config apply`. The v0.2-only adapter and warning reporter are
removed once direct v0.2.0 upgrade/rollback leaves the supported transition
matrix; this is not a permanent alternate configuration schema.

Do not edit the installed YAML in place. Copy it to a separate candidate, edit
that file, then run `sudo <prefix>/bin/ioc-config apply <candidate.yml>`. The
helper stages service-readable bytes and runs strict unknown-key, binding,
conversion, semantic and registry checks in a configuration-only Spring context
with the installed service environment and JVM overrides. It then atomically
replaces the live file and waits for application health. If startup fails, it
preserves the rejected candidate and restores the previous configuration.
`check [candidate.yml]` runs the same side-effect-free semantic phase and does
not open SQLite, initialize transports or compose the runtime graph.

### Two-step canonical validity activation

An existing installation must not combine binary/schema rollout and destructive
legacy-row activation in one unreviewed restart:

1. Deploy the TTL-capable binary while keeping
   `ioc.lifecycle.validity.mode: disabled` and
   `existing-records: reject`. Wait for local health to become `UP`; this
   compatibility start applies additive migrations without expiring business
   rows.
2. Stop intake and optional remote synchronization. Preserve one exact rollback
   point containing the active immutable application release, the complete
   operator configuration, `ioc-dataframe.db` and `ioc-service.db` (including
   their SQLite side files when present). Do not copy a live database with an
   ordinary filesystem copy; use the deployment backup transaction or a
   SQLite-consistent backup procedure.
3. Set `mode: fixed`, a positive `fixed-ttl`, and
   `existing-records: expire`, then restart the same binary. Startup admission
   archives and removes every pre-activation canonical row before intake or
   export opens. Mutable CSV projections may legitimately become empty; this
   activation does not create an insert-driven export revision.
4. Wait for health `UP` and verify the active projections. New accepted source
   observations then repopulate and confirm records under fixed validity.

The additive dataframe migration also installs the reusable export-slot
registry. Its first eligible active export seeds the existing external IDs;
later exports preserve survivor slots and reuse the smallest holes. No separate
service-DB migration or operator command is required, but the registry is part
of the dataframe backup/rollback boundary.

Activation is durable and one-way for these databases. A later `mode: disabled`
fails startup with `LIFECYCLE.POLICY_MISMATCH`. Rollback after activation is not
a config edit and not a partial database restore: stop the service and restore
the matching pre-activation application release, configuration and both SQLite
databases as a single recovery point. Files moved or remote side effects after
that point require separate operational reconciliation.

## Systemd contract

The rendered service:

- runs as the dedicated non-login account;
- sets the prefix as `WorkingDirectory`;
- forces daemon mode and the servlet health surface on the command line;
- loads `etc/application.yml` and `etc/ioc-extractor.env`;
- runs strict binding and semantic configuration validation in `ExecCondition`;
  invalid configuration skips activation without entering `Restart=on-failure`,
  so a deterministic configuration error cannot create a restart storm;
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
