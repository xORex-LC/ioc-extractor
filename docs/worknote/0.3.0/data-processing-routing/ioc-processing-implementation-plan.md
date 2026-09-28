# Configurable IOC processing implementation plan

Status: implementation sequence, 2026-09-27. P0 semantic contracts and source
closure are complete in [P0 evidence](p0-semantic-contracts.md). P1 parsing
and the P2 shared mapper/operator-plan admission are implemented. P3 and P4
have explicit document and processed-import execution seams, but production
selection remains gated by P5 policy identity/recovery work. Import still uses
the compatible previous path in production. Reviewed P0
source baseline: `e8cb0aeb83ca6776f771951320163573ac0bbce4`.
Companion: [Router plan](router-implementation-plan.md).

## Scope and reuse boundaries

Support configured original/derived views, host cleanup, view-specific
classification, artifact/field selection and final-row identity/reduction for
document extraction and processed import. Existing AS_IS import is unchanged.
Only new observations use the activated policy; existing rows are not rewritten.
The hypothetical `(IP, country)` case is a composite-identity fixture, not a new
feed, artifact, geolocation feature or database migration requirement.

Reuse the source owners identified in [task design](camel-task-design.md) and
[reuse inventory](reuse-inventory.md). In particular, the current
`ConfigurableRowMapper` imports application `ClassifiedIndicator`; simply moving
it into `ioc-processing` would create the wrong dependency direction. Extract
minimal pure view/mapping inputs first. Keep canonical keys, winner policies,
write plans, import authority and receipts in application/storage owners.

Candidate `ioc-processing` is a pure module depending inward on domain and admitted
platform contracts, never application, CSV, Camel or Spring. P0 must demonstrate
this dependency closure before admission. Domain parser/normalizer/classifier
remain the single semantic implementations; the module composes them rather than
recreating equivalent algorithms.

## Ordered slices

| Slice | Work and touched owners | Exit evidence |
|---|---|---|
| P0 — semantic contracts and closure (complete) | Jointly with R0 define original/derived values, provenance, absence/failure, classification binding, candidate output and input cardinality. Audit mapper/provider imports and select what moves versus remains. | [P0 evidence](p0-semantic-contracts.md): acyclic dependency map, reviewable input/output fixtures and separate pure-module decision. |
| P1 — parser and views (implemented) | Extend shared parsing behind `RegexIndicatorExtractor`, feature extraction and normalization as needed; implement host derivation without DNS/network I/O. Preserve original occurrence/span/source metadata. | [P1 evidence](p1-parser-views.md): customer URL and IP:port/path examples, bare values, invalid authority/port/query/fragment cases, full-cell versus document parsing, RE2/J and JDK parity; explicit unsupported-form outcomes. |
| P2 — shared mapping and configuration (implemented) | Extract/adapt `ConfigurableRowMapper`, providers/transforms and classification inputs once. Extend `IocProperties`, registry metadata and semantic preflight for named views, field overrides and classifications; compile IOC bindings for Router. | [P2 evidence](p2-shared-mapping.md): strict key/argument admission, whole-element overlay fixture, coverage/omission checks, bounded unary operations and shared mapper tests. |
| P3 — document integration (implemented, activation gated) | Change `IocExtractionService` entry after attribution and before unconditional deduplication/classification for the admitted flow. Preserve occurrences, legacy plan behavior and stage checkpoints. Adapt `PrepareArtifactsStage`/`CsvArtifactPreparer` to reuse final key resolution and `ArtifactOccurrenceSelector`. | [P3 evidence](p3-document-integration.md): cleanup collisions resolved on final fields; original blacklist preserved while masks use host; configurable match codes; source ranks/counts and failure-policy parity; no writes on fail-fast rejection. |
| P4 — processed import integration (implemented, activation gated) | Split `DataframeImportRowMapper` input admission from output finalization; evolve `ProcessedImportRowPreparer` and use explicit IOC input/output bindings in the new route instead of CSV provider inference. Reuse admitted operations, add accepted-row warnings separately from rejection issues. | [P4 evidence](p4-processed-import.md): ABSENT/NULL/VALUE preserved; full-cell parsing, final keys after processing, required-primary and source-authority checks; one logical row without Cartesian expansion; compound conflicts rejected; AS_IS fixtures unchanged. |
| P5 — policy identity and activation | Extend `ProcessingPolicyFingerprint` and compiled import contract identity with plan/order and semantic versions. Inspect pinned delivery/run recovery and define mismatch disposition before enabling new policies. | Restart and in-flight policy-change fixtures; receipt-based post-commit finalization never reprocesses committed input. No backfill; coexistence and TTL behavior tested. |
| P6 — customer qualification and retirement | Run both flows with the qualified Router, remove replaced mapper/dispatch paths and promote architecture/configuration/operations documentation. | Golden fixtures, deterministic recovery tests, resource measurements, full quality evidence and operator activation guidance. |

P1 and pure P2 work can progress after P0 while Router is built. P3/P4 execution
depends on R2/R3 contracts and runtime; P5 must precede production activation.
P6 and R5 share end-to-end evidence instead of maintaining two duplicate suites.

## Required behavior matrix

| Scenario | Acceptance |
|---|---|
| Two URLs become the same host | Final configured identity and winner policy decide reduction; no hardcoded domain-only global deduplication. |
| Same IP, different synthetic country fields | A configured composite key preserves two candidates/records; an IP-only key follows its configured collision policy. No production country artifact is introduced. |
| Field bound to original while default view is host | Only that field's value-dependent providers use original; constants and source/ID semantics remain explicit. |
| Operator changes classification codes | Existing declarative rules remain authoritative, evaluated against the configured view. |
| All demanded consumers recover | One final WARN for the original expected failure; checkpoint can accept. A demanded strict consumer retains ERROR. No diagnostic is downgraded after publication. |
| Import fallback succeeds | Accepted-row warning reaches bounded reporting without becoming a row rejection or weakening authority/output validation. |
| Unexpected operation exception | Stops invocation through the existing failure path; no fallback or hidden retry. |
| Crash at durable boundary | Preserve document per-artifact commit/projection semantics and import cross-artifact transaction/receipt semantics; do not claim document-wide atomicity. |
| Policy change with existing data | No rewrite/rekey/backfill. Normal TTL removes old records only when lifecycle is active; disabled lifecycle does not promise expiry. |

The final-output key formula is not a Router concern. Do not change the current
canonical conflict policy into global last-write-wins merely because an in-memory
candidate selector supports LAST_NONEMPTY. Review durable and batch collision
semantics separately and preserve their configured contracts.

## Activation and unresolved implementation gates

- P0 adopts the resolution worknote's semantics for IOC integration. Exact
  operator descriptor syntax and channel-specific binding still require P2
  executable validation; earlier illustrative YAML is not supported config.
- Inspect accepted-warning persistence/report summaries before choosing whether
  a schema change is necessary. No migration is assumed merely to add diagnostics.
- A policy fingerprint identifies semantics but does not preserve executable old
  plans. P5 must choose and test a supported drain/pinning/mismatch procedure;
  pending deliveries must never silently acquire new semantics.
- Moving pure mapping code must not lower protection by moving it out of the
  domain/application coverage scopes. Review per-module ratchets/floors and the
  moved behavior's tests in the same change.

## Build-quality and completion

Apply the [shared admission checklist](camel-task-design.md#build-quality-admission-required-in-every-module-adding-slice)
when admitting `ioc-processing` and when relocating existing production sources.
Update test inventories, JaCoCo scope/floor/ratchet records and report dependencies,
SpotBugs membership and exact finding review, CPD/PMD source roots and aggregate
membership, ArchUnit rules and module documentation. Keep all existing floors;
do not compensate for lost tests by changing denominators or exclusions.

Use focused domain/mapper/import tests during development. Each completed code
slice then runs `make verify` and separately `make pmd-analysis` on the final
worktree, plus applicable watchlist review. Inspect coverage and raw analyzer
findings after relocation; a same-count finding replacement still needs review.
Published capability docs, configuration examples, module maps and generated
diagnostic references must match the final implementation. Do not hand-edit
generated catalogs or reference local worknotes from published docs.

Done means both input paths satisfy the behavior matrix through one shared
semantic implementation, canonical/recovery authority is preserved, activation
is documented and tested, replaced code is removed, and the full quality gate
includes every new production module. A successful document-only example is not
completion of this plan.
