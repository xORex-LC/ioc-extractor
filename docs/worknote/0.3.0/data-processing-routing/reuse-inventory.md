# Existing mechanisms and extension ownership

Concrete configuration and compiled route proposal: [configuration/execution design](configuration-execution-design.md).

See [task-level Camel design](camel-task-design.md) for the refined document-entry
and import-validation seams, work packages T1–T8 and new-observations-only cutover.

Status: source-backed design inventory, 2026-09-26, HEAD `f43037ee87ef`.
All paths below describe existing code, not proposed APIs. Reuse means preserving
one semantic owner; it does not require retaining an unsuitable package location.

The [Router analysis](router-boundary.md) adds a new narrow generic selection
owner; existing IOC predicates bind into it rather than being copied.

## Reuse / extend / move / new

| Responsibility | Existing owner | Disposition |
|---|---|---|
| Ordered stages, envelope and observation | `platform-etl`: PipelineRunner, Envelope, Stage, PipelineObserver | Reuse; keep outer orchestration and diagnostic delivery; no IOC routing rules here |
| Literal refanging and normalization | `ioc-domain`: Refanger, IndicatorNormalizer | Reuse; do not add a second defanging pipeline |
| Address features and host classification | DefaultIndicatorFeatureExtractor, HostClassifier | Extend with shared address component parsing; host derivation and classification must agree |
| Configured match rules | RuleBasedMatchPolicy and application IndicatorClassifier | Reuse; bind selected views/profiles; do not create a second classifier |
| Artifact selection, providers and transforms | ConfigRegistryCatalog, ConfigurableRowMapper, CsvArtifactDefinition | Extend view/capability binding; move pure preparation semantics inward, leaving CSV mechanics in adapter |
| Record and lookup formulas | ArtifactIdentityConfigurationResolver, ArtifactIdentityDefinition, CanonicalArtifactKeyResolver | Reuse configured key columns/modes/definition IDs; no parallel identity DSL or hash implementation |
| Existing identity compatibility | CanonicalArtifactIdentityResolver, ArtifactIdentityStore and JDBC implementation | Preserve legacy bridge/drift protection; new normalized identities need activation evidence |
| Occurrence preservation | DeduplicateIndicatorsStage and classified occurrence payloads | Reuse original positions; early dedup must not erase candidates required by an artifact policy |
| Whole-candidate selection within preparation | ArtifactOccurrenceSelector, ArtifactWritePolicy | Extend existing policy vocabulary only for approved missing behavior |
| Ordered field updates across deliveries | LatestRegisteredValuePolicy, FieldValueOrigin, registered observation contracts | Reuse ranks and equal-rank conflict checks; no scheduler-time winner |
| Import cell authority and null handling | ImportCell, ImportMergePolicy, ImportMergeResolver | Reuse; authority and ordering are distinct decisions, not competing merge engines |
| Processed import preparation | CsvProcessedImportRowPreparer | Replace concrete CSV-mapper/provider-name coupling with the shared preparation owner |
| Canonical mutation | CanonicalArtifactWriter, JdbcCanonicalMutationEngine, JdbcCanonicalImportWriter | Reuse transaction/receipt/lifecycle owners; no graph-node writes |
| Configuration admission/versioning | IocProperties, IocSemanticConfigurationCheck, ArtifactPolicyCatalog, ProcessingPolicyFingerprint | Extend strict binding, collect-all diagnostics and fingerprints; no second configuration loader |
| Explain/trace | PipelineDecisionTracer, LoggingPipelineDecisionTracer, diagnostics and pipeline observers | Extend operation/view/route/key outcomes; no new telemetry subsystem |
| Concurrency and recovery | platform-concurrency, intake ledgers, reconciliation and private import workspace | Reuse existing owner per work unit; no per-node durable queue |
| Named derived views and typed operation dependencies | No equivalent shared contract today | New narrow capability, placed according to the module proposal |

## Concrete evidence and important limits

- [Key resolver](../../../../core/ioc-application/src/main/java/com/iocextractor/application/artifact/CanonicalArtifactKeyResolver.java)
  supports composite and first-nonempty formulas. Composite values preserve
  configured column order and normalize blanks/literal NULL to null; an entirely
  empty key is absent. Reuse this encoding deliberately. Missing country in a
  country-sensitive artifact needs an explicit admission rule, not a new encoder.
- [Identity definition](../../../../core/ioc-application/src/main/java/com/iocextractor/application/artifact/ArtifactIdentityDefinition.java)
  separates record keys from alternative match keys. The hypothetical `(ip,
  country)` schema is representable as a composite key; adding an actual new
  artifact still needs schema/configuration/import/export qualification.
- [Write policy](../../../../core/ioc-application/src/main/java/com/iocextractor/application/artifact/policy/ArtifactWritePolicy.java)
  currently supports KEEP_FIRST and LAST_NONEMPTY selection, and one ordered
  field-update policy. This is not a general whole-record latest-wins engine.
- [Occurrence selector](../../../../core/ioc-application/src/main/java/com/iocextractor/application/artifact/policy/ArtifactOccurrenceSelector.java)
  selects one complete candidate, taking the last nonblank selection-field value
  in its supplied list, with first-candidate fallback. It does not compare
  durable admission ranks or merge columns from several rows.
- [CSV preparer](../../../../adapters/adapter-csv/src/main/java/com/iocextractor/adapter/out/sink/csv/CsvArtifactPreparer.java)
  uses retained indicators for legacy selection and all occurrences for
  occurrence-aware selection. The latter maps first, groups by identity and
  invokes the selector. Preserve that semantic distinction when moving code.
- [Ordered field policy](../../../../core/ioc-application/src/main/java/com/iocextractor/application/artifact/policy/LatestRegisteredValuePolicy.java)
  compares registered origins, retains existing values for blank input, rejects
  contradictory equal rank and distinguishes public changes from origin-only
  advancement. Reuse it when adding policy strategies.
- [Import merge policies](../../../../core/ioc-application/src/main/java/com/iocextractor/application/dataframeimport/model/ImportMergePolicy.java)
  include keep-existing, fill-missing, replace-non-null, authoritative and
  reject-conflict. Their authority ceilings are part of source admission.
- [Import writer](../../../../adapters/adapter-store-jdbc/src/main/java/com/iocextractor/adapter/out/store/jdbc/JdbcCanonicalImportWriter.java)
  collects matching active records and rejects multiple matches. It resolves
  tri-state merges, passes eligible ordered fields onward, recalculates the
  final key and rejects stable-identity changes. Preserve this composition.

## Hypothetical identity and matching scenario

There is no current country-based artifact or defect asserted here. This example
tests future schema flexibility; it adds no geolocation or feed requirement.
For such a future country example, configure record identity `(ip, country)` and normally
the same full matching tuple. An IP-only match key may locate multiple countries;
even a single match with a different country must not be updated into another
identity. Reuse existing ambiguity/stable-identity rejection. Do not introduce
first-match fallback to conceal an under-specified import contract.

Within an artifact, equality is equality of its configured final key, not
equality of a globally normalized IOC. Metadata enters identity only when
explicitly configured as a key column. The same IOC can therefore yield one row
in one artifact and several in another. Canonical ID, lifecycle ID, export slot
and business key remain separate concepts.

## Policy composition, not a universal merge switch

Apply these responsibilities in order:

1. Prepare and validate the selected representation and artifact fields.
2. Resolve identity and allowed existing-row matches.
3. Select whole candidates where the artifact policy requires it.
4. Admit field instructions through import authority/tri-state rules when importing.
5. Apply configured field precedence against canonical state and commit once.

This is a semantic ordering, not permission to reorder the import writer's
existing staging/validation transactions. In particular, source authority must
not be weakened by a latest-wins selector, and a rejected delivery must not
discard evidence needed for diagnostics or renewal.

If whole-record latest-wins across deliveries is required, extend the existing
write-policy family with explicit row origin, allowed mutable columns and null
semantics. Preserve IDs, key fields and lifecycle facts. Do not claim that
LAST_NONEMPTY plus one ordered name field already provides this behavior.
Whether to implement that additional strategy in the first slice remains a
scope decision; the configuration model must leave a coherent extension point.

## Migration and regression obligations

Move/replace old semantic ownership in the same slice that admits the new owner.
Temporary compatibility adapters may translate old configuration into the new
compiled model, but must not evaluate a second copy of the rule. Keep one
authoritative definition of keys and write policies referenced by document and
import flows.

Tests must exercise existing unchanged configuration, early dedup/occurrences,
post-cleaning collisions, IP-country separation, partial/ambiguous import
matches, tri-state authority, opposite completion order, equal-rank conflict,
provenance-only updates and lifecycle renewal. Existing TCKs and fixtures are
the starting point; add tests for new observable contracts, not mirrored internals.
