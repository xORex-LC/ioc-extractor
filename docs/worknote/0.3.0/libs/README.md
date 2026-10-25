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

## Current work order — 2026-10-24

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
this qualification. The owner selected Spring Boot configuration next: C1
implements the bounded inspection contract with C0 corrections. C2 adds explicit
source policies, restricted-context qualification and an executable closed-input
consumer guide. C3 promotes
[ADR 0046](../../../ADR/0046-reusable-spring-boot-configuration-inspection.md),
the consumer/reference/maps and publication-admission documentation, with unchanged
Maven/analyzer/coverage scope. C4 adds the standalone POM, dependency mediation,
packaged API/guide consumers and selected-repository routing; its checkpoint in
the [configuration plan](configuration-library-design.md) records local evidence.
PR 18 integrated configuration into `release-0.3.0`.
`io.github.xorex-lc:ioc-config-spring-boot:0.3.0-rc.1` is published to Central and
GitHub Packages from the immutable validated bundle. The GitHub cold consumer
passed in run `40088095516`; Central passed locally after correcting a
same-URL Resolver filter conflict. The original workflow concealed Central's
failure behind `tee`. Routing and pipeline-failure fixes have regression coverage;
review/integration and a protected CI recovery run remain next. The
[configuration release record](configuration-library-design.md#protected-publication-record--2026-10-24)
separates the original failure, local recovery and immutable release identity.
The owner selected events next on 2026-10-24. Its existing E0/two-unit design is
retained; E1 implements JDK-contract documentation and 43 focused tests, strict
Javadoc and a compiled/executed consumer example. E2 implements the explicit Spring bridge, IOC migration and the three corrected
failure boundaries with real-context regressions. E3 adds both standalone POMs
and explicit packaging selectors with exact dependency checks. E4 adds isolated
JDK/Spring consumers, eight-file provenance, dependency mediation/negatives and
missing-core routing probes with positive controls. The regular CI library gate
requires them; E5 still blocks signing and remote writes. Both modules use
`io.github.xorex-lc`. Publication requires E5 and a separate release action. ETL remains
deferred pending a second consumer.

Diagnostics publication is recorded in the release branch's D5 documentation
(commit `ce5675e2a74243ede83211cca090570976c6bbce`). The older diagnostics checkpoint
below describes local admission before publication; it is not the current
publication status. This analysis does not reconcile that separate documentation
commit into `r030-libraries` or republish either existing library.

## Previous diagnostics checkpoint — 2026-10-18

Diagnostics extraction and publication are complete. PR 16 integrated the
implementation into `release-0.3.0`; protected run
[`39137777538`](https://github.com/xORex-LC/ioc-extractor/actions/runs/39137777538)
published `ioc-platform-diagnostics-api:0.3.0-rc.1` and qualified separate Central
and GitHub Packages consumers. Concurrency's published release and tag remain
unchanged. No next library implementation has been selected. Events E1–E5 remain
deferred; configuration and logging retain their proposals, and ETL remains
deferred for lack of a confirmed second use case.

Diagnostics D0 is complete for design: artifact/packages, 18-type public surface,
maintainer, metadata/suppression seams and compatibility are fixed in its
contract. D1 extraction and IOC migration are implemented, including API
Javadoc/package docs, module READMEs and a consumer guide with compiled/executed
examples. D2 public-boundary qualification is complete: generic and IOC
regressions, budget/merge/delivery limits and failure ownership are verified.
D3 independent packaging is complete: both components pass snapshot/RC
empty-cache consumers, and diagnostics executes all guide examples. D4 promotes
[ADR 0044](../../../ADR/0044-reusable-diagnostics-contract.md), reviews the final
documentation and closes local admission with the recorded final gates.
D5 closes authorized publication and repository qualification; its
[release record](lib-2-diagnostics-design.md#d5-protected-publication-record--2026-10-18)
retains source, signing, deployment and consumer evidence. A real second
production-service integration remains future work.

## Candidate documents

- [Diagnostics contract and extraction plan](lib-2-diagnostics-design.md): D0
  decisions, D1–D5 evidence, migration seams and published release identity.
- [Diagnostics ADR promotion record](lib-2-diagnostics-adr-draft.md): preserved
  draft path pointing to the accepted ADR 0044.
- [Spring Boot configuration proposal](configuration-library-design.md): existing
  mechanics, C1 inspection contract, service ownership, extraction slices and
  publication-tooling prerequisites.
- [C0 qualification results](configuration-c0-analysis.md): 54 synthetic scenarios,
  actual Boot binding, extraction blockers and revised v1 support requirements.
- [Configuration reuse after C1](configuration-extension-analysis.md): proposed
  error/migration boundaries, reproduced output defects, command-source isolation,
  test recipes and dependency/publication qualification.
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


Historical local diagnostics checkpoint: D1 has a coherent shared-model
implementation, documented public API and
[consumer guide](../../../guides/diagnostics-library.md) with executable
examples. Its [qualification record](lib-2-diagnostics-design.md#d1-implementation-and-qualification-record--2026-10-18)
retains migration, documentation and analyzer review. D2 boundary qualification
is complete. D3 standalone packaging/consumption and D4 final local admission
are qualified. D5 repository consumers promote the artifact to
`published-qualified`; [the inventory](../evidence/shared-code-inventory.md)
records the release alongside the concurrency pilot.
