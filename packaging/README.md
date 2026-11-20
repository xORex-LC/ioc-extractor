# packaging

## Purpose and boundary

Host installation, local delivery and application-release asset tooling. This
is a shell/template directory, not a Maven module. It installs the bootable JAR
from `bootstrap/ioc-app`; it does not reimplement IOC extraction, canonical
storage or synchronization. Reusable-library publication tooling lives elsewhere.

[Deployment, upgrade and rollback](../docs/guides/deployment.md) owns operator
procedures, prerequisites and troubleshooting. This reference maps the local
scripts and their actual boundaries. Use packaging files from the selected
application source release, together with its matching JAR and release notes.

## Contents and entry points

| Source | Responsibility |
|---|---|
| [install.sh](install.sh) | Root installer: validate prefix/artifact, provision account/JDK/layout, render helpers/unit and optionally start |
| [install-layout.sh](install-layout.sh) | Sourced prefix, source-tree, marker, user and config-candidate safety helpers; no work when executed alone |
| [deploy-local.sh](deploy-local.sh) | Ordinary-user checkout build and delegation of privileged activation |
| [deploy-local-root.sh](deploy-local-root.sh) | Internal root activation, stopped-DB backup, storage health gate and bounded rollback |
| [uninstall.sh](uninstall.sh) | Remove the fixed service unit; preserve prefix by default, purge only explicitly |
| [prepare-release-artifacts.sh](prepare-release-artifacts.sh) | Validate application release identity/packaged metadata and create immutable public asset names |
| [publish-release-draft.sh](publish-release-draft.sh) | Create/repair a GitHub draft using supplied assets; compare existing asset bytes |
| [templates](templates/README.md) | Host YAML/env, systemd unit, CLI launcher and configuration helper |
| [tests](tests/README.md) | Temporary-directory contracts, rendering and evidence limits |

## Host dependencies and identity

Debian 11/12 are the documented installer baselines. Other GNU/Linux/systemd
hosts, CPU/libc combinations and Java majors require target qualification.
The scripts check required commands; they do not install OS packages. A target
needs Bash, GNU filesystem/archive tools, curl/CA trust, account tools and systemd
utilities. GNU tar ACL/xattr support is required for deployment DB preservation.
No Maven, DB server or Office installation is needed to run a prepared JAR.
A source build additionally needs Git, the Maven wrapper and full JDK 21.

The installer can use system Java 21+ or a SHA-256-verified archive. Built-in
archive URLs/digests are pinned in `install.sh` for Linux x86-64/AArch64; custom
archives/URLs require `--jdk-sha256`, and downloads require HTTPS. Archive reuse
requires its saved digest marker and an executable Java 21+. Accepting a newer
major is not evidence that it has been qualified. Keep Java outside `/home`, which
`ProtectHome` hides; embedded SQLite native loading also needs a usable temp mount.

Defaults differ: installer/uninstaller use `/opt/ioc-extractor`, local deployment
uses `/srv/ioc-extractor`. Always supply the actual prefix when switching tools.
The service name is fixed as `ioc-extractor`; separate prefixes are not independent
service instances. Serialize install/deploy/config/uninstall operations as an
operator: their locks do not provide one shared host-lifecycle transaction.

## Installed layout and ownership

```text
<prefix>/
├── jdk/                         # private Java unless system Java is selected
├── releases/<release-id>/       # root-owned JAR and checksum; local deploy adds metadata
├── current -> releases/<id>     # relative active-release link
├── bin/ioc, bin/ioc-config      # rendered root-owned helpers
├── etc/                        # operator YAML/env and installation marker
├── backups/                    # local deployment DB archives and unit sidecars
├── var/
│   ├── db/                     # canonical/service SQLite DBs and WAL/SHM
│   ├── document-preparation/   # private streamed-source/preparation scratch, created by runtime
│   ├── export/                 # immutable slices/manifests/completion evidence
│   ├── inbox/ processing/ done/ failed/
│   ├── import/                 # inbox/processing/snapshots/staging/terminal/quarantine
│   └── ledger/ logs/
└── dataframe/                  # generated mutable projections
```

Executables/config remain root-owned; runtime state and projections belong to the
non-login service user. The installer reconciles runtime directories/files to
`0750`/`0640`; local upgrades explicitly reconcile the managed-import directories.
SQLite canonical truth and coordination ledgers must be backed up together.
CSV projections do not replace them. See [storage](../docs/dev/storage.md).

## `install.sh`

The script's `--help` is the flag reference. `--prefix`, explicit `--jar` and
matching `--checksum` avoid ambiguity; absent `--jar`, autodiscovery requires
exactly one candidate. An adjacent `.sha256` is used automatically when present.
A checksum compares bytes; use a trusted source for it and for the JAR.

Prefix validation rejects protected roots/subtrees, symlink traversal, source
checkouts and unrelated nonempty directories. The marker binds prefix, service
and non-root account. A recognized pre-marker release layout can be adopted;
the 0.1.0 single-directory layout requires the documented side-by-side transition.
`--force` permits live config replacement, not a bypass of these safety checks.

Installation stops an existing active instance before changing Java/release/state,
moves recognized legacy SQLite families only while stopped, stages the JDK/JAR,
and atomically switches `current`. An existing release ID is reused only for equal
JAR bytes. Unknown schema/layout combinations require manual reconciliation.

Existing YAML/env remain operator-owned; changed templates become `.new` candidates.
A different unreconciled `.new` causes `PACKAGING.CONFIG_CANDIDATE_CONFLICT` with
paths/digests and review commands, not file contents. `--force` can overwrite live
configuration and must not be used merely to bypass reconciliation.

The installer writes the systemd unit even when systemd is not PID 1, but cannot
start or qualify the service then. `--no-start` skips startup; an existing service
stopped by the installer remains stopped. Normal startup requires the service to
be active and all three local storage health components `UP`. Failure recovery
attempts to restore the previous link/unit and restart a previously active service.
It does **not** restore databases, changed config, Java or runtime permissions;
it is not the local-deployment DB rollback transaction.

## `deploy-local.sh`

Run from a separate checkout as an ordinary user. It rejects a build JVM older
than 21, holds a per-user local deployment lock and always runs Maven `clean verify`.
There is no skip-tests deployment mode. Exactly one bootable application JAR must
result. `--allow-dirty` permits a dirty checkout with a visible timestamped identity;
otherwise the initial checkout must be clean. Defaults retain five releases and
five DB/unit backup sets; health polling defaults to 15 attempts at two-second
intervals, plus request execution time.

The before/after source check compares porcelain status, not content fingerprints
or final `HEAD`. It can miss edits to an already-dirty file or a commit during the
build. Freeze the checkout throughout build/activation; `--allow-dirty` does not
provide source provenance. See [TOOL-11](../docs/KNOWN-ISSUES.md#tool-11).
The privileged phase verifies supplied/staged JAR checksums and writes local
release metadata; it does not itself verify embedded commit identity or a tag.

For an existing installation, the root phase prepares config candidates, refreshes
unit/helpers and stages the release. It then validates effective installed config
with the new JAR as the service account, before stopping the active process. This
is configuration-only validation, not DB migration or transport qualification.
The stop/backup/activation path archives the complete `var/db` with ACLs/xattrs,
switches `current`, starts and checks storage health. Unit and DB sidecars share
the incoming release ID; retention prunes those backup files as pairs.

The armed ERR path restores the previous link, previous unit and DB archive when
available, preserves failed DB state separately and attempts restart/health.
That rollback scope does not include refreshed CLI helpers, Java, operator YAML,
source moves, projections, export slices or remote effects. Explicit preflight
`die` exits can bypass restoration and leave new host files beside the old JAR:
[OPS-13](../docs/KNOWN-ISSUES.md#ops-13) remains open. Inspect/reconcile the saved
unit and matching helpers before a later restart. Initial bootstrap has no prior
release to restore and stops an unhealthy first deployment.

`--port` always becomes the unit's CLI `--server.port` override and the probe port.
Installer `--server-port` renders that override only when explicitly supplied;
its probes/config helper still use the chosen/default port. Keep unit, YAML,
helper and health-client ports aligned. Remote sync is not a storage promotion
gate. Full accepted service behavior still requires an operator smoke scenario.

## `uninstall.sh`

Root-only removal validates prefix, account and installation shape. Without
`--purge` it stops/disables the service and removes its unit, retaining prefix,
config, user and data. `--purge` deletes the validated prefix and attempts account
removal, requiring the exact installation marker. Review backups before this
irreversible operation. A pre-marker layout must first be safely adopted before
purge; use the matching old procedure for a 0.1.0 installation.

## Configuration contract

Installed YAML overrides classpath defaults. Env, JVM system properties and CLI
have higher precedence. Strict `ioc.*` admission and full list replacement apply;
packaging does not merge YAML or replace the application's configuration validator.
The fresh template selects fixed validity `12h` with `existing-records: reject`;
classpath lifecycle remains disabled. Managed import, sync and source-file retention
are disabled until explicitly configured. See [template reference](templates/README.md).

Use a separate candidate with `ioc-config check/apply`. Its preflight checks
configuration only; `apply` restores YAML on failed aggregate health, not runtime
DB/file/remote effects. Lifecycle activation is a durable one-way cutover for the
DB pair; disabling it is not rollback. Preserve the matching binary/config/DB
recovery point and use the [lifecycle activation procedure](../docs/guides/canonical-record-lifecycle.md)
and [deployment guide](../docs/guides/deployment.md) rather than changing this boundary
through installer flags. Binary/key compatibility windows are release-specific;
[ADR 0027](../docs/ADR/0027-rollback-safe-configuration-evolution.md) records the alias policy.

<a id="systemd-hardening"></a>

## Systemd contract

The unit forces daemon/servlet mode, loads the environment file, runs configuration
validation in `ExecCondition` and grants writes only to `var`/`dataframe`. JVM and
cgroup bounds, capabilities, address families and filesystem restrictions live in
the [unit/template reference](templates/README.md). Deterministic config
rejection can skip startup rather than enter a restart loop; inspect condition
output as well as unit state. Generic network egress restrictions cannot infer
operator-owned SMB endpoint addresses. The environment owns network segmentation.

## Release helper contracts

`prepare-release-artifacts.sh` accepts only `vX.Y.Z` / `vX.Y.Z-rc.N` matching the
non-SNAPSHOT project version. Validate-only mode checks that pair; preparation
also checks embedded version/commit/UTC build time and exact lightweight `--version`
output. It writes the public JAR and SHA-256 sidecar without overwriting existing
asset paths. Key/value metadata goes to stdout; progress/errors go to stderr.
The helper consumes caller-supplied source identity; it does not validate Git tags.

`publish-release-draft.sh` checks names, supplied notes and checksum before GitHub
operations. `--dry-run` stops after local checks and does not verify a remote tag or
release. Normal mode needs `gh`, `GH_TOKEN` and `GH_REPO`. It creates only drafts,
can repair draft metadata/missing assets, compares existing assets byte-for-byte
and refuses replacement or modification of published metadata/assets.

The [release workflow](../.github/workflows/release.yml) owns annotated remote-tag
and exact checked-out commit validation, one reactor build, source-tree cleanliness
and transfer of the same prepared assets to draft creation. Do not attribute those
workflow checks to a standalone helper invocation. General rules belong to
[RELEASE-PROCESS](../docs/RELEASE-PROCESS.md); library publication is separate.

## Automated packaging gate and related docs

Run from the repository root:

```bash
make lint-shell
make docs
```

The shell gate and [packaging tests](tests/README.md) use temporary
fixtures, source assertions and rendered units. They do not install the service,
qualify every rollback path or establish a target's operational acceptance.
`make verify` supplies Java/runtime contracts separately.

Operator references: [deployment](../docs/guides/deployment.md),
[configuration](../docs/guides/configuration.md), [daemon operations](../docs/guides/daemon-operations.md),
[resource sizing](../docs/guides/bulk-resource-sizing.md) and
[remote sync](../docs/guides/remote-storage-sync.md). Fetch requires remote read/list;
publish additionally requires create/write/rename/delete for owned temporary state.
Managed import needs its separately provisioned/qualified private namespace and
[import operations](../docs/guides/dataframe-import.md).
