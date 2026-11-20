# ioc-extractor

*An ETL pipeline that turns messy documents into durable, ready-to-deliver IOC reputation lists.*

[![CI](https://github.com/xORex-LC/ioc-extractor/actions/workflows/ci.yml/badge.svg)](https://github.com/xORex-LC/ioc-extractor/actions/workflows/ci.yml)
[![Codecov](https://codecov.io/gh/xORex-LC/ioc-extractor/branch/release-0.3.0/graph/badge.svg?precision=2)](https://app.codecov.io/gh/xORex-LC/ioc-extractor/tree/release-0.3.0)
[![Quality Tooling](https://img.shields.io/badge/Quality%20Tooling-SpotBugs%20%7C%20PMD%20%7C%20CPD-informational)](docs/dev/build-quality.md)
![Java](https://img.shields.io/badge/Java-21-orange)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.0-6DB33F)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue)](LICENSE)

**ioc-extractor** reads documents that contain Indicators of Compromise, refangs
obfuscated values such as `hxxp[:]//` and `[.]`, detects and normalizes IOCs,
classifies network masks, attributes their source from document section headers,
and stores the result in canonical SQLite storage. From that durable truth it
maintains reputation-list CSV projections and can produce immutable delivery
slices for remote publication.

```text
messy document
      │
      ▼
 refang → detect → attribute → deduplicate → classify → prepare → policy checkpoint
      │
      ▼
canonical SQLite truth → mutable CSV projections
                      └→ immutable export slices → optional SMB publish
```

## Features

- **Document input through Apache Tika**: HTML, PDF, DOCX and XLSX are covered
  by release contract tests; other configured parser formats are best effort.
- **Safe IOC extraction** with RE2/J by default and an optional JDK regex engine;
  all configured patterns remain RE2-compatible.
- **Configurable refang and classification** with ordered replacement rules,
  Public Suffix List awareness and a declarative four-variant mask policy.
- **Canonical SQLite storage** with stable cross-run artifact identity,
  configurable per-artifact field mutation, source provenance and
  crash-recoverable ledgers. Legacy artifacts retain keep-first behavior.
- **Config-driven CSV artifacts** with independent schemas, filters, ID spaces,
  providers and transforms. A genuinely new sink or wire format remains an
  adapter-level extension.
- **One-shot and daemon operation**: run a command and exit, or continuously
  ingest stable files from an inbox with retry, recovery and retention.
- **Canonical validity** with active-only reads, explicit existing-DB activation,
  retained history and stable sparse reusable IDs for immutable exports.
- **Bounded preparation** through required document/processed-import routing plans,
  owned source workspaces and configurable independent execution budgets.
- **Managed dataframe import**: validate and ingest recognized export-shaped
  CSV deliveries from dedicated local or SMB inboxes through durable recovery.
- **Immutable exports and remote synchronization**: export complete profile
  slices, fetch source documents over SMB and publish completed slices using
  durable ledgers plus periodic reconciliation.
- **Operational visibility** through typed diagnostics, ECS JSON logs, a CLI
  health view and a loopback actuator endpoint in daemon mode.
- **Enforced architecture**: ArchUnit, Maven Enforcer and contract tests verify
  module boundaries, configuration and documentation during `verify`.

## Quick start

JDK 21 and GNU Make are required. Maven itself is not required because the
repository includes the Maven Wrapper.

```bash
git clone https://github.com/xORex-LC/ioc-extractor.git
cd ioc-extractor

# Discover the supported developer commands and run the release-quality gate.
make help
make doctor-core
make verify

APP_VERSION="$(./mvnw -q help:evaluate -Dexpression=project.version -DforceStdout)"
APP_JAR="bootstrap/ioc-app/target/ioc-app-${APP_VERSION}.jar"

java -jar "${APP_JAR}" --version
# Synthetic input is available in a clean checkout; private source documents are not.
make fixture SIZE=1000 SEED=42
# Both commands share isolated storage under .dev/oneshot.
make extract SOURCE=.dev/fixtures/ioc-1000-seed-42.html
make export PROFILE=reputation-lists
```

An `extract` run adds new rows to the existing canonical database; it does not
replace the accumulated dataset. Use `--dry-run` to execute extraction without a
durable write. Exit code `3` means the run completed with error diagnostics and
may still have committed valid rows under `collect-and-continue` policy.

Run `java -jar "${APP_JAR}" --help` or `<prefix>/bin/ioc --help` after installation for the
current command surface. Main commands are `extract`, `export`, `sync`, `import`
and `health`.

## Run modes

### One-shot CLI

`oneshot` is the default. The requested command runs and the process exits. It is
suited to manual extraction, export and synchronization jobs.

### Daemon

Daemon mode polls `./var/inbox`, waits until a file is stable, claims it into
`./var/processing`, writes valid data to canonical storage and moves the source
to `./var/done` or `./var/failed`. Export and remote-sync schedulers run in the
same long-lived process; the actuator health endpoint binds to loopback. Full
polling scans are the production default; filesystem watch notifications are
optional latency hints. This direct invocation uses the current working directory;
use `make runtime-up` for an isolated developer daemon.

```bash
java -jar "${APP_JAR}" --ioc.runtime.mode=daemon
```

For production, use the packaged systemd deployment rather than starting the
daemon manually. See the [deployment guide](docs/guides/deployment.md) and
[daemon operations guide](docs/guides/daemon-operations.md).

## Data and delivery model

SQLite is the business-data source of truth. Files under `dataframe/` are
regenerated mutable projections and must not be edited as input. One-shot and
daemon ingestion both accumulate into the same canonical model.

| Layer | Purpose |
|---|---|
| Canonical dataframe DB | Durable rows, non-reusable canonical IDs, lifecycle, deduplication and provenance |
| CSV projections | Current mutable view of configured reputation artifacts |
| Immutable export slice | Consistent profile snapshot, manifest/marker and profile/artifact-scoped reusable export slots |
| Remote publication | Idempotent delivery of completed slices through a publish ledger |

Default projections include masks, bare IPv4 addresses, an address blacklist,
file hashes, and the standalone `IOC_aggregate_generated.csv` carrier artifact.
Its immutable output belongs to the separate `ioc-aggregate` profile. Schemas
are configured in the packaged defaults and explained in
[processing](docs/dev/processing.md). The external delivery contract is described
in [artifact export](docs/dev/artifact-export.md).

## Configuration

The packaged defaults are embedded in the runnable jar. Repository-local
overrides may be placed in `./configs/application.yml`; installed deployments
load `<prefix>/etc/application.yml`. CLI options, system properties and
environment variables have higher precedence.

The complete operator-facing property reference, accepted values and selection
guidance live in the [configuration guide](docs/guides/configuration.md). A full
production override template is available at
[packaging/templates/application.yml](packaging/templates/application.yml).

List overrides replace the whole list; supply all required elements/fields.
Classpath validity defaults to disabled; fresh production templates enable fixed
12-hour validity. Existing databases require the explicit
[lifecycle activation](docs/guides/canonical-record-lifecycle.md) procedure.

The `ioc.*` boundary is strict: unknown YAML keys, CLI properties, system
properties and `IOC_*` environment variables fail startup instead of being
silently ignored.

## Deployment

The supported deployment tooling targets systemd hosts and provisions a
dedicated account, JDK 21, immutable releases, operator-owned configuration and
runtime directories. Debian 11 and 12 are the tested installer baselines; other
compatible distributions are best effort.

```bash
sha256sum "${APP_JAR}" > "${APP_JAR}.sha256"
sudo packaging/install.sh --prefix /srv/ioc-extractor \
  --jar "${APP_JAR}" --checksum "${APP_JAR}.sha256" --no-start
```

The example installs a locally built candidate and leaves it stopped for
configuration review. Use a dedicated prefix outside the source checkout. For a
released JAR, obtain its trusted checksum and matching packaging files from the
release; a checksum calculated locally does not authenticate downloaded content.

For repeatable deployment of the current checkout to a local Debian/WSL test
runtime, including `verify`, database backup, health gating and rollback:

```bash
./packaging/deploy-local.sh --prefix /srv/ioc-extractor
```

See [packaging/README.md](packaging/README.md) for the script reference and the
[deployment guide](docs/guides/deployment.md) for installation, upgrade,
configuration reconciliation, rollback and uninstall procedures.

## Repository layout

The project is a multi-module Maven reactor. Dependencies point inward. Adapter
modules isolate a coherent technology or integration boundary behind domain or
application ports; one boundary may use several cooperating libraries when they
belong to the same integration family.

```text
platform/   generic cross-cutting libraries plus IOC-specific support
core/       pure IOC domain/processing, application use cases, ports and test support
adapters/   Tika, RE2/J, CSV, JDBC/SQLite, SMB, ingest and CLI boundaries
bootstrap/  Spring Boot composition root and the only runnable module
build-support/ build-only aggregate reports; never a runtime dependency
packaging/  host installer, deployment scripts and systemd templates
tools/      reproducible developer, smoke, security and CI leaf commands
docs/       project maps, capability guides, ADRs and operator guides
```

## Documentation

- [Engineering principles](docs/PRINCIPLES.md), [architecture](docs/ARCHITECTURE.md)
  and [module map](docs/MODULARIZATION.md) — system maps with paired Russian
  versions linked from each document; [boundaries](docs/BOUNDARIES.md) describes
  their executable enforcement.
- [Processing](docs/dev/processing.md), [storage](docs/dev/storage.md),
  [ingestion](docs/dev/ingestion.md), [artifact export](docs/dev/artifact-export.md),
  [sync](docs/dev/sync.md), [configuration](docs/dev/configuration.md) and
  [observability](docs/dev/observability.md) — runtime capability maps for developers.
- [Build quality](docs/dev/build-quality.md) — Maven gates, analyzer scope,
  reports and the strict SpotBugs exact-finding ratchet.
- [Operator guides](docs/guides/README.md) — deployment, complete configuration,
  daemon operation and remote storage synchronization.
- [Security engineering](docs/SECURITY-ENGINEERING.md) and
  [release process](docs/RELEASE-PROCESS.md) — project-wide operational policy.
- [ADRs](docs/ADR/README.md) — historical decisions with English/Russian pairs and
  preserved original provenance.
- [Known issues](docs/KNOWN-ISSUES.md) — open debt and deliberate seams.

## Development

The complete test lifecycle, taxonomy, focused commands and report contract are
documented in [Testing](docs/TESTING.md).

```bash
make test
make test-integration
make verify

# Focus one module and build its upstream dependencies.
make test-module MODULE=core/ioc-domain

# Run one class or method.
make test-one MODULE=core/ioc-domain TEST=DefaultIndicatorNormalizerTest#strips_trailing_semicolon
```

Reproducible helper commands for fixtures, isolated daemon runtime, smoke tests,
ECS log queries and local CI leaves are documented in
[tools/README.md](tools/README.md). Fixtures and isolated runtime commands keep their state under gitignored
`.dev/`; invoking the application without a workspace uses its working directory:

```bash
make doctor-dev
make fixture SIZE=1000 SEED=42
make smoke
make bootstrap  # installs the pinned repo-local lychee binary without sudo
make pre-push   # reactor, PMD, packaging and local-link CI leaves
```

Additional CI jobs qualify packaged library consumers and advisory reporting;
`make pre-push` is not the entire workflow matrix. CI runs the complete reactor
gate, packaging safety contracts and documentation
link check on pushes and pull requests. Contribution rules are documented in
[CONVENTIONS.md](docs/CONVENTIONS.md).

## Technology stack

Java 21 · Spring Boot 4 · Spring Integration · Spring JDBC · SQLite JDBC ·
HikariCP · picocli · Apache Tika · RE2/J · Apache Commons CSV · Guava PSL · SMBJ ·
Jackson · SLF4J 2 / Logback ECS · JUnit 5 · AssertJ · ArchUnit

## License

[MIT](LICENSE) © 2026 Misyurkeev Denis
