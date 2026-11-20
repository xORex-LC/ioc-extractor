# Architecture Decision Records

## Purpose

These records explain why a decision was made at a particular point in time:
context, alternatives, trade-offs, consequences and subsequent dated amendments.
They are historical evidence, not a description of the current implementation.
For current behavior, follow the [architecture map](../ARCHITECTURE.md),
[module map](../MODULARIZATION.md) and [capability documentation](../dev/).

Accepted decisions are append-only. A new decision or a dated superseding note
records a change; earlier reasoning remains available. One record covers one
coherent decision or direction, identified by its stable `NNNN-slug.md` path.
A historical implementation plan, status or example must be read in its dated context.

## Languages and historical preservation

The canonical paths below contain English. Russian counterparts are under `ru/`.
The translation migration covers ADR-0000–ADR-0048 and does not audit their decisions
against current code or update their historical implementation claims.

- ADR-0000–ADR-0019 were written in Russian. Their English versions are labelled
  translations; the Russian originals are retained under `ru/`.
- ADR-0020–ADR-0048 were written in English. Those original files remain byte-for-byte
  unchanged; their Russian versions are labelled translations.
- As explicitly approved for this migration, moving the Russian originals changes
  **only relative Markdown link addresses**, so they still resolve to the same
  **English canonical destinations**. Decision prose, dates, statuses and examples
  remain unchanged. Original bytes remain available in the recorded Git revisions.
- Fenced historical examples, including comments and diagram labels, are reproduced
  verbatim in translations. They are historical exhibits, not newly qualified commands.
- Translations retain explicit anchors for headings in the original language, keeping
  existing fragment links usable at the canonical paths.

[Translation provenance](translation-provenance.tsv) records each source's baseline
revision/hash, the migration input revision/hash, and both resulting paths/hashes.
The separate migration identity preserves the previously approved URL-only repair
in ADR-0025 without overwriting its earlier baseline identity.
[Link rebases](translation-link-rebases.tsv) enumerates every approved address change
in relocated Russian originals, including repeated occurrences.

## Records

| ID | Decision | English | Russian |
|---|---|---|---|
| ADR-0000 | Foundational decisions | [EN](0000-foundations.md) | [RU](ru/0000-foundations.md) |
| ADR-0001 | Streaming ingestion (daemon) | [EN](0001-streaming-ingestion.md) | [RU](ru/0001-streaming-ingestion.md) |
| ADR-0002 | Configurable artifact filling and four-variant classification | [EN](0002-output-mapping-and-matching.md) | [RU](ru/0002-output-mapping-and-matching.md) |
| ADR-0003 | Diagnostics subsystem and logging | [EN](0003-diagnostics.md) | [RU](ru/0003-diagnostics.md) |
| ADR-0004 | Extraction, patterns and test corpus | [EN](0004-extraction-and-test-corpus.md) | [RU](ru/0004-extraction-and-test-corpus.md) |
| ADR-0005 | Services, pipeline and boundary enforcement | [EN](0005-services-and-pipeline.md) | [RU](ru/0005-services-and-pipeline.md) |
| ADR-0006 | Refinements following design review | [EN](0006-design-review-refinements.md) | [RU](ru/0006-design-review-refinements.md) |
| ADR-0007 | Logging/observability as a separate subsystem (ECS) | [EN](0007-logging-observability.md) | [RU](ru/0007-logging-observability.md) |
| ADR-0008 | Stages 6–8 implementation review and technical debt | [EN](0008-stage-6-8-implementation-followups.md) | [RU](ru/0008-stage-6-8-implementation-followups.md) |
| ADR-0009 | Module granularity and criteria for splitting the domain | [EN](0009-modularization-granularity.md) | [RU](ru/0009-modularization-granularity.md) |
| ADR-0010 | Daemon Health / Actuator HTTP boundary | [EN](0010-health-actuator.md) | [RU](ru/0010-health-actuator.md) |
| ADR-0011 | Synchronization with external storage (delivery/SMB) | [EN](0011-remote-sync.md) | [RU](ru/0011-remote-sync.md) |
| ADR-0012 | Dataframe emission and export over DB truth (streaming) | [EN](0012-streaming-dataframe-emission.md) | [RU](ru/0012-streaming-dataframe-emission.md) |
| ADR-0013 | Hybrid coordination: control-plane events + edge IO + ETL core | [EN](0013-event-driven-coordination.md) | [RU](ru/0013-event-driven-coordination.md) |
| ADR-0014 | Event-driven coordination of the full chain: ingest → export → delivery | [EN](0014-event-driven-ingest-to-delivery.md) | [RU](ru/0014-event-driven-ingest-to-delivery.md) |
| ADR-0015 | Retire legacy CSV lookup/storage mode | [EN](0015-retire-legacy-csv-lookup-storage.md) | [RU](ru/0015-retire-legacy-csv-lookup-storage.md) |
| ADR-0016 | Configuration reliability: unified preflight + strict binding | [EN](0016-config-preflight-strict-binding.md) | [RU](ru/0016-config-preflight-strict-binding.md) |
| ADR-0017 | Diagnostics as a first-class processing outcome (OBS-D1) | [EN](0017-diagnostics-first-class-outcome.md) | [RU](ru/0017-diagnostics-first-class-outcome.md) |
| ADR-0018 | Typed ECS structured logs: event fields outside MDC | [EN](0018-typed-ecs-structured-logging.md) | [RU](ru/0018-typed-ecs-structured-logging.md) |
| ADR-0019 | Spring Boot 4 baseline and nested ECS wire format | [EN](0019-spring-boot-4-nested-ecs.md) | [RU](ru/0019-spring-boot-4-nested-ecs.md) |
| ADR-0020 | Canonical record validity and expiration lifecycle | [EN](0020-canonical-record-expiration-lifecycle.md) | [RU](ru/0020-canonical-record-expiration-lifecycle.md) |
| ADR-0021 | Stable reusable export slots | [EN](0021-stable-reusable-export-slots.md) | [RU](ru/0021-stable-reusable-export-slots.md) |
| ADR-0022 | Revision-significant identical export candidates | [EN](0022-revision-significant-identical-export.md) | [RU](ru/0022-revision-significant-identical-export.md) |
| ADR-0023 | Bounded lifecycle reconciliation runtime state | [EN](0023-bounded-lifecycle-reconciliation-runtime.md) | [RU](ru/0023-bounded-lifecycle-reconciliation-runtime.md) |
| ADR-0024 | Managed contract-driven dataframe import | [EN](0024-managed-dataframe-import.md) | [RU](ru/0024-managed-dataframe-import.md) |
| ADR-0025 | Managed-import SMB service namespace and terminal retention | [EN](0025-managed-import-smb-service-namespace-and-terminal-retention.md) | [RU](ru/0025-managed-import-smb-service-namespace-and-terminal-retention.md) |
| ADR-0026 | Fail-closed SMB encryption policy | [EN](0026-fail-closed-smb-encryption-policy.md) | [RU](ru/0026-fail-closed-smb-encryption-policy.md) |
| ADR-0027 | Rollback-safe configuration evolution | [EN](0027-rollback-safe-configuration-evolution.md) | [RU](ru/0027-rollback-safe-configuration-evolution.md) |
| ADR-0028 | Publish the concurrency pilot as an independent library | [EN](0028-pilot-library-publication.md) | [RU](ru/0028-pilot-library-publication.md) |
| ADR-0029 | Release-branch publication with component tags | [EN](0029-release-branch-library-publication.md) | [RU](ru/0029-release-branch-library-publication.md) |
| ADR-0030 | Registered observation order for mutable artifact fields | [EN](0030-registered-observation-order-for-artifact-fields.md) | [RU](ru/0030-registered-observation-order-for-artifact-fields.md) |
| ADR-0031 | Bounded Camel preparation runtime | [EN](0031-bounded-camel-preparation-runtime.md) | [RU](ru/0031-bounded-camel-preparation-runtime.md) |
| ADR-0032 | Invocation-owned IOC semantic reuse | [EN](0032-invocation-owned-ioc-semantic-reuse.md) | [RU](ru/0032-invocation-owned-ioc-semantic-reuse.md) |
| ADR-0033 | Bounded semantic reuse during import staging | [EN](0033-bounded-semantic-reuse-during-import-staging.md) | [RU](ru/0033-bounded-semantic-reuse-during-import-staging.md) |
| ADR-0034 | Required Router processing plans | [EN](0034-required-router-processing-plans.md) | [RU](ru/0034-required-router-processing-plans.md) |
| ADR-0035 | Generation-owned mutable artifact projections | [EN](0035-generation-owned-mutable-projections.md) | [RU](ru/0035-generation-owned-mutable-projections.md) |
| ADR-0036 | Sealed document preparation workspace | [EN](0036-sealed-document-preparation-workspace.md) | [RU](ru/0036-sealed-document-preparation-workspace.md) |
| ADR-0037 | Profile-scoped export execution | [EN](0037-profile-scoped-export-execution.md) | [RU](ru/0037-profile-scoped-export-execution.md) |
| ADR-0038 | Durable bounded document execution and writer admission | [EN](0038-durable-bounded-document-execution.md) | [RU](ru/0038-durable-bounded-document-execution.md) |
| ADR-0039 | Workspace-owned streamed document source processing | [EN](0039-streamed-document-source-processing.md) | [RU](ru/0039-streamed-document-source-processing.md) |
| ADR-0040 | Native sequential preparation dispatch | [EN](0040-native-sequential-preparation-dispatch.md) | [RU](ru/0040-native-sequential-preparation-dispatch.md) |
| ADR-0041 | SQLite staged publication experiment | [EN](0041-sqlite-staged-publication-experiment.md) | [RU](ru/0041-sqlite-staged-publication-experiment.md) |
| ADR-0042 | Retain Native SQLite production storage | [EN](0042-native-sqlite-production-storage.md) | [RU](ru/0042-native-sqlite-production-storage.md) |
| ADR-0043 | Configurable CSV record endings and independent quoting | [EN](0043-configurable-csv-output-format.md) | [RU](ru/0043-configurable-csv-output-format.md) |
| ADR-0044 | Extract a JDK-only diagnostics contract | [EN](0044-reusable-diagnostics-contract.md) | [RU](ru/0044-reusable-diagnostics-contract.md) |
| ADR-0045 | Extract typed SLF4J logging and a scalar ECS profile | [EN](0045-reusable-slf4j-logging-contract.md) | [RU](ru/0045-reusable-slf4j-logging-contract.md) |
| ADR-0046 | Extract Spring Boot configuration declaration inspection | [EN](0046-reusable-spring-boot-configuration-inspection.md) | [RU](ru/0046-reusable-spring-boot-configuration-inspection.md) |
| ADR-0047 | Separate local event contracts from explicit Spring delivery | [EN](0047-explicit-spring-control-event-adapter.md) | [RU](ru/0047-explicit-spring-control-event-adapter.md) |
| ADR-0048 | Independent library versions and reuse before duplication | [EN](0048-independent-library-versions-and-reuse.md) | [RU](ru/0048-independent-library-versions-and-reuse.md) |

## Authoring

New ADRs are written in English using the next sequential number. Include status,
context, decisions with rationale and rejected alternatives, consequences and any
open questions. Reflect accepted outcomes in their owning current documentation;
link back to the ADR for the historical rationale.

Follow [documentation conventions](../CONVENTIONS.md#languages-and-translations).
The migration exception above is limited to preserving link destinations when
relocating these originals; it does not permit rewriting historical decisions.
