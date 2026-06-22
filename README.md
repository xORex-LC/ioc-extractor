# ioc-extractor

*An ETL pipeline for Indicators of Compromise (IOC): from a messy document to ready-to-use reputation lists.*

[![CI](https://github.com/xORex-LC/ioc-extractor/actions/workflows/ci.yml/badge.svg)](https://github.com/xORex-LC/ioc-extractor/actions/workflows/ci.yml)
![Java](https://img.shields.io/badge/Java-21-orange)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.3-6DB33F)
![Build](https://img.shields.io/badge/build-Maven%20(multi--module)-C71A36)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue)](LICENSE)

**ioc-extractor** ingests a document (often a Word export — `cp1251`, disorganized),
**refangs** defanged IOCs (`hxxp[:]//`, `[.]`, `[:]`), detects and normalizes them,
classifies network masks, attributes a `source` from section headers, de-duplicates,
and emits several reputation artifacts as CSV. It runs both as a one-shot CLI and as a
streaming daemon with partitioning and aggregation.

```text
hxxps[:]//bad[.]example[.]com/down/x.exe   ─┐
sub[.]example[.]com                          │   refang → detect → classify       masks / ip_list /
185[.]100[.]87[.]250                         ├──────────────────────────────▶  address_blacklist / hashes
1[.]2[.]3[.]4:8080/payload[.]exe             │   attribute → dedup → write        (CSV reputation lists)
e3b0c44298fc1c149afbf4c8996fb924…           ─┘
```

## Features

- **Refang** of defanged IOCs — ordered replacement rules (in config, not code).
- **Detection** on the **RE2/J** engine (linear-time, ReDoS-safe; `java.util.regex` fallback).
- **Mask classification** via the **Public Suffix List** — a declarative 4-variant `url_match`/`host_match` scheme.
- **Source attribution** from header markers (`БИБ-…`, `Письмо ФСТЭК России …`).
- **Multiple artifacts from a single run** — each with its own schema, id-space and normalization.
- **Config-driven DSL** — a new output format is added by editing `application.yml` (column specs + provider/transform/filter registries), **no code**.
- **Two run modes**: a one-shot CLI (`extract`) and a streaming **daemon** (Spring Integration: `inbox` → partitions → aggregation with stable ids, ledger, retry, crash recovery).
- **Observability**: ECS-JSON logs (Logback) + a diagnostics subsystem behind ports.
- **Clean hexagonal architecture** whose boundaries are enforced by the **build** (ArchUnit + Maven Enforcer), not by review.

## Pipeline

Pipes-and-Filters on top of `platform-etl`; every stage is observed by a `PipelineObserver`:

```text
ReadSource → Refang → ExtractIndicators → AttributeSource → Deduplicate → WriteArtifacts
```

## Quick start

Requires **JDK 21** (not available in Debian repositories — install it manually; see [Deployment](#deployment-debian-11)). Maven is not required — a wrapper `./mvnw` is committed.

```bash
git clone https://github.com/xORex-LC/ioc-extractor.git
cd ioc-extractor

# build (only the bootstrap/ioc-app module produces a bootable jar)
./mvnw -q -DskipTests package          # -> bootstrap/ioc-app/target/ioc-app-0.1.0.jar

# one-shot run
java -jar bootstrap/ioc-app/target/ioc-app-0.1.0.jar extract --source source/ioc-source.htm [--dry-run]
```

## Daemon mode

With `ioc.runtime.mode=daemon`, sources are dropped into `./var/inbox`, pass a
stability quiet-period, are written as partitions under `./dataframe/partitions`,
and are periodically aggregated into canonical CSVs with stable ids. See
[docs/ingestion.md](docs/ingestion.md).

```bash
java -jar bootstrap/ioc-app/target/ioc-app-0.1.0.jar --ioc.runtime.mode=daemon
# then drop *.htm / *.html / *.docx into ./var/inbox
```

## Output artifacts

| Artifact | File | Contents |
|---|---|---|
| `masks` | `masks_list_generated.csv` | network masks (domains, subdomains, URLs, IP-with-port) with `url_match`/`host_match` codes |
| `ip_list` | `ip_list_generated.csv` | bare IPv4 addresses |
| `address_blacklist` | `address_blacklist_generated.csv` | two columns: `forbidden_url` (domains/URLs/IP-URLs) and `forbidden_ip` (bare IP only) |
| `hashes` | `hashes_list_generated.csv` | MD5 / SHA1 / SHA256 (UPPER-cased), routed to per-algorithm columns |

Schemas, normalization and column filling are described in [docs/output-mapping.md](docs/output-mapping.md).

## Configuration

The single source of truth is [bootstrap/ioc-app/src/main/resources/application.yml](bootstrap/ioc-app/src/main/resources/application.yml),
under the `ioc.*` tree (`runtime`, `source`, `refang`, `engine`, `patterns`,
`classify`, `sink`, `lookup`, `aggregation`, `ingestion`). Override order:

```text
classpath:application.yml  <  ./configs/application.yml  <  CLI flags / env
```

Everything source-, output- and policy-specific lives in config, never hard-coded.

## Deployment (Debian 11+)

[packaging/install.sh](packaging/install.sh) installs JDK 21 (from a tarball, **no
apt repositories**), creates a dedicated system user, lays out the directories,
deploys the jar + config and brings up a hardened systemd unit in daemon mode.

```bash
sudo packaging/install.sh                 # prompts for the directory (default /opt/ioc-extractor)
systemctl status ioc-extractor
journalctl -u ioc-extractor -f
```

Offline host: `sudo packaging/install.sh --jdk-tarball /path/temurin-21.tar.gz`.
See [packaging/README.md](packaging/README.md) for details.

## Repository layout

A multi-module Maven reactor; **one external adapter = one library**:

```text
platform/   cross-cutting subsystems behind ports (errors, diagnostics, etl, observability)
core/       ioc-domain (pure Java) + ioc-application (use cases, ports, stages, ingest, aggregation)
adapters/   one library each: regex-re2j · psl · source-tika · lookup-csv · sink-csv · ingest · cli-picocli
bootstrap/  ioc-app — composition root + Spring Boot entry point (CLI/daemon, no web)
```

## Documentation

Principles, architecture and conventions live in [docs/](docs/) (in Russian):

- [architecture.md](docs/architecture.md) — Clean Hexagonal + Onion, layers, the dependency rule;
- [modularization.md](docs/modularization.md) — the reactor module map;
- [pipeline.md](docs/pipeline.md) — the Pipes-and-Filters pipeline;
- [output-mapping.md](docs/output-mapping.md) — declarative artifact filling;
- [ingestion.md](docs/ingestion.md) — the streaming daemon, partitions and aggregation;
- [boundaries.md](docs/boundaries.md) — architectural boundary enforcement (ArchUnit + Enforcer);
- [diagnostics.md](docs/diagnostics.md) · [logging.md](docs/logging.md) — diagnostics and ECS observability;
- [principles.md](docs/principles.md) · [conventions.md](docs/conventions.md) — engineering principles and conventions.

## Development

```bash
./mvnw test       # unit tests across the reactor
./mvnw verify     # the real gate: tests + ArchUnit (boundaries) + Enforcer + golden e2e
```

Architectural boundaries are kept by the build, not by review ([docs/boundaries.md](docs/boundaries.md)).
CI runs `./mvnw -B -ntp -T 1C verify` on every push and pull request.

## Stack

Spring Boot · Spring Integration · picocli · Apache Tika · RE2/J (regex, JDK
fallback) · Apache Commons CSV · Guava (Public Suffix List) · Logback + ECS
encoder · JUnit 5 / AssertJ · ArchUnit.

## License

[MIT](LICENSE) © 2026 Misyurkeev Denis
