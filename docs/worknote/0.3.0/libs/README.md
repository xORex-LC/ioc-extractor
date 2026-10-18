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

## Current work order — 2026-10-18

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
  mechanics, supported-contract limits, service ownership, extraction slices and
  publication-tooling prerequisites.
- [C0 qualification results](configuration-c0-analysis.md): 54 synthetic scenarios,
  actual Boot binding, extraction blockers and revised v1 support requirements.
- [LIB-3 logging proposal](lib-3-logging-design.md): SLF4J comparison, generic
  field/scope contracts, explicit masking, IOC migration and publication plan.

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


Diagnostics D1 now has a coherent shared-model implementation, documented public
API and [consumer guide](../../../guides/diagnostics-library.md) with executable
examples. Its [qualification record](lib-2-diagnostics-design.md#d1-implementation-and-qualification-record--2026-10-18)
retains migration, documentation and analyzer review. D2 boundary qualification
is complete. D3 standalone packaging/consumption and D4 final local admission
are qualified. D5 repository consumers promote the artifact to
`published-qualified`; [the inventory](../evidence/shared-code-inventory.md)
records the release alongside the concurrency pilot.
