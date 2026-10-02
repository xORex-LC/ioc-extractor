# Configurable data processing and routing

Status: design accepted for staged implementation, 2026-09-27. R0 bounded
admission, R1 module/compiler skeleton, R2 selection/execution, R3 typed
outcomes/recovery and R4 lifecycle/observability hooks are complete; R5 has
synthetic qualification, while production integration and R5 handover remain
outstanding. Historical
proposal status in earlier assessments records their place in the decision process.

- [R0 admission](r0-admission.md): minimal contracts, module disposition and executed
  runtime probe; durable decision in ADR 0031.
- [R1 implementation](r1-implementation.md): module admission, compiler skeleton,
  quality evidence and remaining execution boundary.
- [R2 implementation](r2-implementation.md): ordered selection, Camel dispatch,
  no-match behavior and quality evidence.
- [R3 implementation](r3-implementation.md): explicit recovery edges, demanded
  failure evidence, selected-branch outcomes and quality evidence.
- [R4 implementation](r4-implementation.md): conditional lifecycle, readiness,
  bounded shutdown, MDC and typed trace bridge with quality evidence.
- [R5 synthetic qualification](r5-qualification.md): reproducible 1k/100k
  non-IOC profile, observed resource costs and remaining integration handover.
- [P0 semantic contracts](p0-semantic-contracts.md): IOC view, occurrence,
  candidate and import-row contracts, source dependency audit and module decision.
- [P1 parser and host views](p1-parser-views.md): shared address grammar,
  exact-cell admission and typed host derivation before plan integration.
- [P2 shared mapping admission](p2-shared-mapping.md): pure IOC module,
  relocated single mapper/classifier and quality-scope admission.

Customer requirement: selectively reduce URL/IP values to a host, including
managed imports, while retaining configurable classification, artifact/field
routing and deterministic data precedence. The owner considers both configured
operation sequences and operator-authored graphs viable; scope and extension
cost should determine the choice.

- [Router cost and architecture analysis](router-cost-and-architecture-analysis.md):
  implementation-level cost mechanisms, diagnostic JFR evidence, framework
  comparison and prioritized optimization experiments at `08d30621`.
- [Camel optimization applicability](camel-optimization-applicability.md):
  fixed-version framework mechanisms, semantic compatibility, an executed
  six-variant dispatch comparison and a Camel-first optimization sequence.
- [Processing optimization plan](processing-optimization-plan.md): common O0–O7
  execution order, ownership and cache/session constraints, risk mitigation,
  paired measurement and build-quality acceptance gates.

- [Router implementation plan](router-implementation-plan.md): technical execution
  slices, framework admission, boundaries and quality gates.
- [IOC processing implementation plan](ioc-processing-implementation-plan.md): shared
  semantics, extraction/import integration, cutover and customer acceptance.
- [Routing resolution and logging](routing-resolution-and-logging.md): exact proposed
  syntax, FIRST/EXCLUSIVE blockage, fallback severity and logging ownership.
- [Configuration and execution design](configuration-execution-design.md): concrete
  proposed YAML, binding ownership, generated Camel plan and failure edge cases.
- [Camel task design](camel-task-design.md): customer scenarios mapped to actual
  classes, processing order, import admission and concrete implementation work.
- [Camel migration assessment](camel-migration-assessment.md): reopened runtime
  decision, adjacent-module replacement, compatibility and qualification gates.
- [Architecture project](architecture-project.md): current gaps, proposed
  contracts, decision alternatives, delivery slices and acceptance criteria.
- [Pattern research](technology-assessment.md): lessons from NiFi, Beam and EIP,
  mapped to our data contracts; no platform adoption is proposed.
- [Reuse inventory](reuse-inventory.md): existing owners, limitations and precise
  extension seams, including identity, candidate selection and import authority.
- [Module design](module-design.md): proposed IOC processing capability,
  independent generic routing module, dependency closure and build obligations.

- [Router boundary](router-boundary.md): detailed generic API, ETL integration,
  failure/concurrency contracts and rationale for a separate Maven module.

- [Router internals](router-internals.md): package/API design, compilation, exact
  selection semantics, patterns, library trade-offs and implementation gates.

The country example throughout this bundle is hypothetical: it tests configurable
multi-field identity, not an existing feed, artifact or production defect.

Qualification baseline: Camel 4.22.1 LTS on Java 21; exact Boot 4.0.8 integration
remains to be verified. Cleanup applies only to new observations; existing rows
expire through the configured lifecycle, with no backfill.

Confirmed Router condition scope: registered parameterized predicates with
AND/OR/NOT; no arbitrary operator expressions or scripts.

Confirmed view flexibility: an artifact branch has a default view, individual
fields may override it, and classification explicitly names its input view.

Confirmed expected-operation failure behavior: dependent branches produce no
row, independent branches may prepare, explicit configuration is required for
fallback, and existing policy/validation boundaries decide whether to write.

Confirmed v1 cardinality: transformations do not expand observations; routing may
select several branches, each producing at most one candidate per input. Document
input is an IOC occurrence; import input is a logical row. Filtering is explicit.

Confirmed semantics: cleaning precedes identity for the affected scenario;
configured artifact key columns determine whether observations collapse. A
country-sensitive artifact may retain two rows with the same IP. Non-key fields
follow an explicit winner/update policy. No global IOC-only uniqueness rule is
introduced. Whole-row selection and per-field merging remain separate policies.

Working recommendation: qualify Camel as a preparation execution adapter against
the JDK-only Router option before implementing a new routing module. Reuse pure
IOC operations and existing identity/mutation policies; replace dispatch in each
migrated scope rather than layering duplicate engines. Preserve canonical and
recovery authority. See the Camel assessment for the staged migration decision.

Source baseline (originally on `r030-libraries`; current branch verified as
`module/platform/router`):
`f43037ee87ef3f4647a90615f04d27a3eb429198`, initially clean worktree;
Java 21 / Spring Boot 4.0.8. Existing `verify` and PMD reports passed on
`a363a129c95a86c1df6fea3eea4e2e5639230792`, but `make context` reports them
not fresh for this HEAD. Research is source inspection and documentation review,
not benchmark, framework compatibility or release qualification evidence.
The baseline above predates R0/R1 implementation. The current branch also
contains ADR 0031, the new Camel adapter and build-quality wiring. See the R0
and R1 evidence notes for executed checks rather than using the original
baseline reports as current evidence.

Related context: [processing](../../../dev/processing.md),
[managed import](../../../dev/dataframe-import.md),
[registered observation order](../../../ADR/0030-registered-observation-order-for-artifact-fields.md),
[ETL library assessment](../libs/lib-4-etl-analysis.md).
