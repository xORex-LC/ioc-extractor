# Project documentation

<a id="документация-ioc-extractor"></a>

Published documentation has four ownership buckets. Follow the document that owns
the question; links provide navigation, while module READMEs and Javadoc provide
local details. The [repository README](../README.md) is the application entry point.

| Bucket | Question answered |
|---|---|
| Root UPPERCASE maps/policies | What applies to the whole project? |
| [dev/](dev/) capabilities | How does one capability work internally? |
| [ADR/](ADR/README.md) decisions | Why did we decide this at that time? |
| [guides/](guides/README.md) | How do operators/integrators use it? |

<a id="карты-и-закон-корень"></a>

## Maps and policies

| Document | Russian | Responsibility |
|---|---|---|
| [PRINCIPLES](PRINCIPLES.md) | [RU](ru/PRINCIPLES.md) | Engineering principles and responsibility |
| [ARCHITECTURE](ARCHITECTURE.md) | [RU](ru/ARCHITECTURE.md) | Runtime flows, layers, durable ownership and recovery |
| [MODULARIZATION](MODULARIZATION.md) | [RU](ru/MODULARIZATION.md) | Module responsibilities and dependency directions |
| [BOUNDARIES](BOUNDARIES.md) | [RU](ru/BOUNDARIES.md) | Executable Maven, ArchUnit and analyzer boundaries |
| [CONVENTIONS](CONVENTIONS.md) | [RU](ru/CONVENTIONS.md) | Documentation ownership, Javadoc, languages and code conventions |
| [SECURITY-ENGINEERING](SECURITY-ENGINEERING.md) | — | Security engineering reference; substantive SDLC revision is outside the 0.3.0 documentation baseline |
| [THREAT-MODEL](THREAT-MODEL.md) | — | Threat-model reference; substantive revision is outside the 0.3.0 documentation baseline |
| [RELEASE-PROCESS](RELEASE-PROCESS.md) | [RU](ru/RELEASE-PROCESS.md) | Standing application/library release rules |
| [TESTING](TESTING.md) | [RU](ru/TESTING.md) | Test lifecycle, meaningful coverage, quality gates and evidence |
| [DIAGNOSTICS-CATALOG](DIAGNOSTICS-CATALOG.md) | — | Generated diagnostic codes and metadata |
| [LOGGING-CATALOG](LOGGING-CATALOG.md) | — | Generated logging actions and fields |
| [KNOWN-ISSUES](KNOWN-ISSUES.md) | [RU](ru/KNOWN-ISSUES.md) | Open defects, qualification limits, deferred work and stable dispositions |

<a id="способности-dev-как-это-работает-внутри"></a>

## Developer capabilities

Cross-module flows, invariants, failure/recovery and extension points. Local
package inventories belong in module READMEs; operator property references
belong in guides. New capability pages use [the template](dev/template.md)
([Russian](dev/ru/template.md)).

| Capability | Russian | Scope |
|---|---|---|
| [processing](dev/processing.md) | [RU](dev/ru/processing.md) | Source extraction, classification, routing, mapping and checkpointed writes |
| [storage](dev/storage.md) | [RU](dev/ru/storage.md) | Canonical SQLite, lifecycle, identity and mutable projection |
| [native-capacity](dev/native-capacity.md) | [RU](dev/ru/native-capacity.md) | Native storage capacity evidence and resource-acceptance limits |
| [ingestion](dev/ingestion.md) | [RU](dev/ru/ingestion.md) | Daemon admission, owned workspaces, retry and recovery |
| [artifact-export](dev/artifact-export.md) | [RU](dev/ru/artifact-export.md) | Immutable snapshots, manifests and export recovery |
| [csv-output-format](dev/csv-output-format.md) | [RU](dev/ru/csv-output-format.md) | CSV bytes, quoting, nulls, encoding and versioned layout |
| [canonical-record-lifecycle](dev/canonical-record-lifecycle.md) | [RU](dev/ru/canonical-record-lifecycle.md) | Validity, expiry/history reconciliation and activation |
| [dataframe-import](dev/dataframe-import.md) | [RU](dev/ru/dataframe-import.md) | Managed local/SMB CSV recognition, staging and promotion |
| [event-coordination](dev/event-coordination.md) | [RU](dev/ru/event-coordination.md) | Control hints, keyed work and durable reconcile |
| [sync](dev/sync.md) | [RU](dev/ru/sync.md) | Remote fetch/publish, landing, ledgers and retries |
| [configuration](dev/configuration.md) | [RU](dev/ru/configuration.md) | Effective sources, strict preflight, check/apply and startup graphs |
| [configuration-inspection](dev/configuration-inspection.md) | [RU](dev/ru/configuration-inspection.md) | Shared inspection integration and service-owned policy |
| [configuration-failure-reports](dev/configuration-failure-reports.md) | [RU](dev/ru/configuration-failure-reports.md) | Controlled startup failures and rendering boundaries |
| [observability](dev/observability.md) | [RU](dev/ru/observability.md) | Diagnostics, completion, typed logs, MDC and disclosure |
| [build-quality](dev/build-quality.md) | [RU](dev/ru/build-quality.md) | Maven quality lifecycle, analyzers, coverage integrity and reports |

<a id="решения-adr-и-гайды"></a>

## Decisions and usage guides

- [ADR index](ADR/README.md) maps all historical English/Russian decision pairs
  and their preservation provenance. Historical status is not current runtime truth.
- [Operator/integrator index](guides/README.md) covers configuration, deployment,
  operations, resource sizing, lifecycle, aggregate, import, routes and SMB sync.
- [Library catalog](guides/libraries/README.md) identifies the six reusable
  artifacts, dependency boundaries and consumer guides. The
  [Russian guide index](guides/ru/libraries/README.md) links their translated guides.
- [Library development](guides/libraries/development.md) and
  [publication](guides/library-publication.md) own reuse, API changes and release
  procedure. Accepted independent version policy remains pending Maven/CI
  activation under TOOL-9; existing publication identities remain immutable.
- [Release records](../.github/release-notes/README.md) and
  [qualification evidence](../.github/release-evidence/0.3.0.md) preserve versioned
  outcomes and limits. Earlier results do not establish current-candidate passes.

<a id="принципы-ведения-документации"></a>

## Maintenance

English canonical documents link required Russian translations. README indexes
are English-only, including those under ru/; their links retain Russian navigation.
Update paired contracts together. Generate diagnostic/logging catalogs from their
owning code, never manually translate them. Follow [CONVENTIONS](CONVENTIONS.md).

Keep current behavior in capability/maps/guides and local contracts next to code.
Accepted ADR history changes only through its controlled append/supersede protocol.
Temporary worknotes are not published authority or link targets. An explicitly
versioned release execution bundle remains planning/evidence; promote durable
outcomes before retiring temporary material.
