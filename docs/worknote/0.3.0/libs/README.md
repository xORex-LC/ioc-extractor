# Shared-library exploration

This directory holds the ongoing interview and code-backed assessment of
existing implementation candidates for reuse outside IOC Extractor.

As of 2026-09-19, all library analysis, design and extraction work shares the
`r030-libraries` branch by owner decision. Earlier branch names in dated
assessments identify historical baselines; they are no longer working branches.

- [Discussion worknote](shared-library-exploration.md): owner answers,
  observations, proposed boundaries, risks and the next question.

These are release worknotes. A recorded design admission is an implementation
target; accepted published ADRs and release/publication evidence remain separate.

## Current work order — 2026-10-20

The owner selected logging tools and a common ECS approach, then authorized L3-0.
The [fixed contract](lib-3-logging-contract.md) closes L3-0: eight public types,
five ECS scalar descriptors, field/MDC/masking rules and explicit IOC wire
corrections. The [62-case fixture](l3-0-probe/README.md) characterizes existing
mechanics and defects with an unrelated catalog. It does not qualify the future
public API. **L3-1 is complete locally**.
The new module and its consumer guide provide public-API tests and documentation.
**L3-2 IOC migration is complete locally**; local facades delegate
to the library and real producer/encoder regressions cover the selected wire fixes.
**L3-3 is complete locally, subject to current unchanged-tree gate receipts**: generic/IOC/backend
test ownership is documented, the reusable Boot recipe has executable examples,
and [ADR 0045](../../../ADR/0045-reusable-slf4j-logging-contract.md) records the
accepted boundary. The next slice is L3-4 publication tooling and cold repository
consumption; those checks and protected publication are not claimed here.
Events E1–E5 remain deferred.

Diagnostics publication is recorded in the release branch's D5 documentation
(commit `ce5675e2a74243ede83211cca090570976c6bbce`). The older diagnostics checkpoint
below describes local admission before publication; it is not the current
publication status. This analysis does not reconcile that separate documentation
commit into `r030-libraries` or republish either existing library.

## Previous diagnostics checkpoint — 2026-10-18

The owner closed events at the design/E0 stage. The next implementation target
is diagnostics: reconcile its September proposal with current code, extract and
migrate the shared contract, qualify an independent consumer, then prepare its
release admission and explicitly authorized publication. Complete that cycle
before starting another library. Events E1–E5 remain deferred; configuration
and logging retain their proposals, and ETL remains deferred for lack of a
confirmed second use case. Concurrency is already published and is not being
released again. Work continues on `r030-libraries`.

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
Publication remains pending explicit authorization.

## Candidate documents

- [Diagnostics contract and extraction plan](lib-2-diagnostics-design.md): D0
  decisions, D1–D4 evidence, migration seams and remaining release gates.
- [Diagnostics ADR promotion record](lib-2-diagnostics-adr-draft.md): preserved
  draft path pointing to the accepted ADR 0044.
- [Spring Boot configuration proposal](configuration-library-design.md): existing
  mechanics, supported-contract limits, service ownership, extraction slices and
  publication-tooling prerequisites.
- [C0 qualification results](configuration-c0-analysis.md): 54 synthetic scenarios,
  actual Boot binding, extraction blockers and revised v1 support requirements.
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
is complete. D3 standalone packaging/consumption is also qualified; public
publication remains pending. The D4 record owns final local admission evidence;
it does not promote the artifact to `published-qualified`.
