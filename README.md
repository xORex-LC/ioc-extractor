# ioc-extractor

*An ETL pipeline that turns messy documents into durable, ready-to-deliver IOC reputation lists.*

[![CI](https://github.com/xORex-LC/ioc-extractor/actions/workflows/ci.yml/badge.svg)](https://github.com/xORex-LC/ioc-extractor/actions/workflows/ci.yml)
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
canonical SQLite truth → CSV projections → immutable export slices → optional SMB publish
```

## Features

- **Format-agnostic input** through Apache Tika: HTML/text, DOCX, PDF and other
  formats supported by the configured parser set.
- **Safe IOC extraction** with RE2/J by default and an optional JDK regex engine;
  all configured patterns remain RE2-compatible.
- **Configurable refang and classification** with ordered replacement rules,
  Public Suffix List awareness and a declarative four-variant mask policy.
- **Canonical SQLite storage** with keep-first cross-run deduplication, source
  provenance, independent artifact identities and crash-recoverable ledgers.
- **Config-driven CSV artifacts** with independent schemas, filters, ID spaces,
  providers and transforms. A genuinely new sink or wire format remains an
  adapter-level extension.
- **One-shot and daemon operation**: run a command and exit, or continuously
  ingest stable files from an inbox with retry, recovery and retention.
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
make verify

APP_VERSION="$(./mvnw -q help:evaluate -Dexpression=project.version -DforceStdout)"
APP_JAR="bootstrap/ioc-app/target/ioc-app-${APP_VERSION}.jar"

java -jar "${APP_JAR}" --version
java -jar "${APP_JAR}" extract --source source/ioc-source.docx
java -jar "${APP_JAR}" export --profile reputation-lists
```

An `extract` run adds new rows to the existing canonical database; it does not
replace the accumulated dataset. Use `--dry-run` to execute extraction without a
durable write. Exit code `3` means the run completed with error diagnostics and
may still have committed valid rows under `collect-and-continue` policy.

Run `java -jar "${APP_JAR}" --help` or `ioc --help` after installation for the
current command surface. Main commands are `extract`, `export`, `sync` and
`health`.

## Run modes

### One-shot CLI

`oneshot` is the default. The requested command runs and the process exits. It is
suited to manual extraction, export and synchronization jobs.

### Daemon

Daemon mode watches `./var/inbox`, waits until a file is stable, claims it into
`./var/processing`, writes valid data to canonical storage and moves the source
to `./var/done` or `./var/failed`. Export and remote-sync schedulers run in the
same long-lived process; the actuator health endpoint binds to loopback.

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
| Canonical dataframe DB | Durable rows, public IDs, deduplication and provenance |
| CSV projections | Current mutable view of configured reputation artifacts |
| Immutable export slice | Consistent profile snapshot with `manifest.json` and `_SUCCESS` |
| Remote publication | Idempotent delivery of completed slices through a publish ledger |

Default projections include masks, bare IPv4 addresses, an address blacklist and
file hashes. Their schemas are configured in the packaged defaults and explained
in [processing](docs/dev/processing.md). The external delivery contract is
described in [artifact export](docs/dev/artifact-export.md).

## Configuration

The packaged defaults are embedded in the runnable jar. Repository-local
overrides may be placed in `./configs/application.yml`; installed deployments
load `<prefix>/etc/application.yml`. CLI options, system properties and
environment variables have higher precedence.

The complete operator-facing property reference, accepted values and selection
guidance live in the [configuration guide](docs/guides/configuration.md). A full
production override template is available at
[packaging/templates/application.yml](packaging/templates/application.yml).

The `ioc.*` boundary is strict: unknown YAML keys, CLI properties, system
properties and `IOC_*` environment variables fail startup instead of being
silently ignored.

## Deployment

The supported deployment tooling targets systemd hosts and provisions a
dedicated account, JDK 21, immutable releases, operator-owned configuration and
runtime directories. Debian 11 and 12 are the tested installer baselines; other
compatible distributions are best effort.

```bash
sudo packaging/install.sh
systemctl status ioc-extractor
journalctl -u ioc-extractor -f
```

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
platform/   framework-free cross-cutting contracts and implementations
core/       pure IOC domain plus application use cases and ports
adapters/   Tika, RE2/J, CSV, JDBC/SQLite, SMB, ingest and CLI boundaries
bootstrap/  Spring Boot composition root and the only runnable module
build-support/ build-only aggregate reports; never a runtime dependency
packaging/  host installer, deployment scripts and systemd templates
tools/      reproducible developer, smoke, security and CI leaf commands
docs/       project maps, capability guides, ADRs and operator guides
```

## Documentation

- [Architecture](docs/ARCHITECTURE.md), [modularization](docs/MODULARIZATION.md)
  and [boundaries](docs/BOUNDARIES.md) — system maps and dependency rules.
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
- [ADRs](docs/ADR/README.md) — immutable decision history.
- [Known issues](docs/KNOWN-ISSUES.md) — open debt and deliberate seams.

## Development

```bash
make test
make verify

# Focus one module and build its upstream dependencies.
make test-module MODULE=core/ioc-domain

# Run one class or method.
make test-one MODULE=core/ioc-domain TEST=DefaultIndicatorNormalizerTest#strips_trailing_semicolon
```

Reproducible helper commands for fixtures, isolated daemon runtime, smoke tests,
ECS log queries and local CI leaves are documented in
[tools/README.md](tools/README.md). They write developer state only under
gitignored `.dev/`:

```bash
make doctor-dev
make fixture SIZE=1000 SEED=42
make smoke
make bootstrap  # installs the pinned repo-local lychee binary without sudo
make pre-push   # same leaf scripts and regular gates as GitHub CI
```

CI runs the complete reactor gate, packaging safety contracts and documentation
link check on pushes and pull requests. Contribution rules are documented in
[CONVENTIONS.md](docs/CONVENTIONS.md).

## Technology stack

Java 21 · Spring Boot 4 · Spring Integration · Spring JDBC · SQLite JDBC ·
HikariCP · picocli · Apache Tika · RE2/J · Apache Commons CSV · Guava PSL · SMBJ ·
Jackson · SLF4J 2 / Logback ECS · JUnit 5 · AssertJ · ArchUnit

## License

[MIT](LICENSE) © 2026 Misyurkeev Denis
