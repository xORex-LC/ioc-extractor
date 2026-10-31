# Shared-library exploration

This directory holds the ongoing interview and code-backed assessment of
existing implementation candidates for reuse outside IOC Extractor.

As of 2026-09-19, library analysis, design and extraction share the
`r030-libraries` branch by owner decision. Reviewed implementations are merged
into the matching release branch before protected publication. Earlier branch
names in dated assessments identify historical baselines.

- [Discussion worknote](shared-library-exploration.md): owner answers,
  observations, proposed boundaries, risks and the next question.

These are release worknotes. A recorded design admission is an implementation
target; accepted published ADRs and release/publication evidence remain separate.

## Current work order — 2026-10-25

Documentation consolidation began on 2026-10-31 with concurrency. Its maintained
[English](../../../guides/libraries/concurrency.md) and
[Russian](../../../guides/ru/libraries/concurrency.md) consumer guides replace
the completed LIB-1 execution worknote. The
[permanent release record](../../../../.github/release-notes/ioc-platform-concurrency-0.3.0-rc.1.md)
preserves hardening dispositions and publication evidence. Other library notes
remain until their corresponding documentation is reviewed and promoted.

Logging L3-0–L3-4 is complete. The
[fixed contract](lib-3-logging-contract.md) defines eight public types, five ECS
scalar descriptors and field/MDC/masking rules. L3-1 implements and documents the
shared module; L3-2 migrates IOC facades and the selected OBS-9/10 wire fixes.
L3-3 qualifies the Boot recipe and promotes
[ADR 0045](../../../ADR/0045-reusable-slf4j-logging-contract.md). L3-4 adds the
exact SLF4J dependency contract, isolated plain/Boot consumers and protected
publication after reviewed integration through PR 17.

`io.github.xorex-lc:ioc-platform-logging-slf4j:0.3.0-rc.1` is published to Central
and GitHub Packages. Separate cold consumers passed against both repositories in
[run 39455105300](https://github.com/xORex-LC/ioc-extractor/actions/runs/39455105300).
The [release record](lib-3-logging-design.md#protected-publication-record--2026-10-21)
retains source/tag, deployment, hashes and consumer evidence. A deployed second
service, live collector/Elasticsearch, load and other providers remain outside
this qualification. Configuration extraction and publication are complete for the six-type API.
Its [release record](../../../../.github/release-notes/ioc-config-spring-boot-0.3.0-rc.1.md)
retains original GitHub success, Central CI failure and local recovery. PR 19
integrated the tooling fixes; protected CI recovery remains [TOOL-8](../../../KNOWN-ISSUES.md).
The owner selected events next on 2026-10-24. Its existing E0/two-unit design is
retained; E1 implements JDK-contract documentation and 43 focused tests, strict
Javadoc and a compiled/executed consumer example. E2 implements the explicit Spring bridge, IOC migration and the three corrected
failure boundaries with real-context regressions. E3 adds both standalone POMs
and explicit packaging selectors with exact dependency checks. E4 adds isolated
JDK/Spring consumers, eight-file provenance, dependency mediation/negatives and
missing-core routing probes with positive controls. The regular CI library gate
requires them. E5 adds protected release admission with successful core-first
qualification, exact dependency handoff and immutable adapter recovery. Both
modules use `io.github.xorex-lc`. PR 19 integrated E1–E5. Both events artifacts
are published at `0.3.0-rc.1` to Central and GitHub Packages: separate cold
consumers passed in core run `40160627532` and adapter run `40162675262`.
The [publication record](lib-5-events-design.md#protected-publication-record--2026-10-25)
retains both immutable identities, original signed bundles and qualification.
Events extraction/publication is complete; ETL remains deferred pending a second
consumer. Configuration's protected CI recovery remains a separate follow-up.

Diagnostics documentation is also consolidated as of 2026-10-31. Its
[English](../../../guides/libraries/diagnostics.md) and
[Russian](../../../guides/ru/libraries/diagnostics.md) guides describe the
implemented API and execute the actual examples through the standalone consumer.
The [permanent release record](../../../../.github/release-notes/ioc-platform-diagnostics-api-0.3.0-rc.1.md)
preserves D0–D5 outcomes, source, signed hashes and repository evidence.
The completed diagnostics plan/draft were removed after promotion to this record,
the guide and accepted ADR 0044. Other libraries retain their own notes.

Configuration documentation is consolidated as of 2026-10-31. The
[English](../../../guides/libraries/config-spring-boot.md) and
[Russian](../../../guides/ru/libraries/config-spring-boot.md) guides cover the six
public types and nested results; the paired restricted-context recipe preserves
the original Java/YAML. Completed design/C0/extension notes and the obsolete C0
source-copy probe were retired after promoting results to the guide, release
record and accepted ADR 0046. Current API/recipe suites own supported behavior.

## Candidate documents

- [LIB-3 logging plan](lib-3-logging-design.md): SLF4J comparison, generic
  field/scope contracts, common ECS profile, masking, IOC migration and publication.
- [LIB-3 ECS analysis](lib-3-logging-ecs-analysis.md): supported-field inventory,
  standard semantics, actual JSON/appender failures, ownership and L3-0 acceptance.
- [LIB-3 fixed L3-0 contract](lib-3-logging-contract.md): exact public surface,
  ECS subset, validation/ownership, masking and chosen producer migrations.
- [LIB-3 ADR promotion record](lib-3-logging-adr-draft.md): historical draft
  pointing to accepted ADR 0045.
- [LIB-3 L3-0 fixture](l3-0-probe/README.md): reproducible 62-case baseline,
  pinned source/dependency identities and qualification limits.

- [LIB-4 ETL assessment](lib-4-etl-analysis.md): existing runner capabilities,
  consumer evidence, contract limits and reasons to defer extraction.

- [LIB-5 local events assessment](lib-5-events-analysis.md): metadata and publisher
  reuse, Spring delivery semantics and the boundary with inter-service messaging.
- [LIB-5 local events proposal](lib-5-events-design.md): proposed module/API
  boundary, Spring failure contracts, qualification slices and confirmed
  service ownership of handler isolation.
- [LIB-5 E0 qualification](lib-5-events-e0-analysis.md): current producers and
  listeners, dependency baseline, real Spring characterization and three
  required bridge corrections before extraction.
- [LIB-5 ADR draft](lib-5-events-adr-draft.md): proposed architectural decision,
  alternatives and consequences for review.
