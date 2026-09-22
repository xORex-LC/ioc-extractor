---
title: "DATA-AGGREGATE-01 — Architecture assessment"
version: "0.3.0"
status: "Discovery in progress"
document_type: "Architecture assessment"
source_of_truth: false
language: "en"
---

# DATA-AGGREGATE-01 — architecture assessment

Status: baseline assessment and discussion history, not an accepted implementation
design. The current engineering proposal is [technical design](technical-design.md);
its explicit decisions supersede earlier alternatives in this assessment.

## Existing extension path

`AppConfig.artifactDefinitions` builds enabled artifacts from configuration.
`ConfigurableRowMapper` prepares ordered public columns without I/O, using
registered providers, transforms and a single-type gate. Storage schemas and
identity definitions are configuration-driven. SQLite remains canonical truth;
mutable CSV and immutable export slices remain projections of that truth.

An additional CSV schema fits the existing CSV/JDBC adapters and application
ports. No new Maven module or dependency is currently justified. Keep framework
and SQL dependencies outside domain/application.

Expected flow:

```text
input observation -> existing extraction/classification -> configured mapping
 -> failure-policy checkpoint -> canonical insert/update/renewal
 -> mutable projection -> eligible immutable export -> configured publication
```

## Confirmed code seams

1. **Hash mapping:** `IocProperties.Sink.Artifact.Column.whenType` and
   `ConfigurableRowMapper.cell` accept only one IOC type. Unconditional `value`
   also emits network values. Candidate solutions are a multi-type gate or an
   explicit hash provider; neither is selected. `address.url` is not a valid
   general hash provider: it returns non-bare-IP values, including URLs.
2. **Ordinary repeat:** `JdbcCanonicalMutationEngine` renews an active row and
   records provenance while preserving public fields. The compatibility
   `JdbcCanonicalArtifactRepository` uses conflict-do-nothing. Neither exposes
   a configured ordinary-ingest latest-name policy today.
3. **Batch ordering:** `DeduplicateIndicatorsStage` retains the first occurrence
   by type/value before artifact mapping. A last-source policy must account for
   this upstream information loss if it includes same-document repetitions.
4. **Shared mapping consumers:** a multi-type DSL change touches binding,
   preflight, `ColumnSpec`, mapper assembly, export fingerprints and
   `CsvProcessedImportRowPreparer`. A new provider also needs review of processed
   import's explicit IOC-provider catalog. Do not implement either only in the
   ordinary output mapper.
5. **Public mutation:** a changed `name` needs coherent canonical transaction,
   recovery receipt, revision/projection and export behavior. Existing managed
   import mutation machinery is a reuse candidate, not proof that ordinary
   ingest already supports this policy.

## Design constraints to resolve

- Define a durable meaning of latest before relying on thread completion order.
  An older retry must not accidentally overwrite a newer accepted name.
- Update matching and public mutation within the existing transactional boundary;
  avoid an unprotected read-then-write outside that boundary.
- Isolate artifact-specific policy; preserve other artifacts' keep-first behavior.
- Distinguish active matching from expired lifecycle restart.
- Do not create all-NULL IOC rows for currently unrepresentable network values.
- New table creation is supported generically, but active-database admission,
  upgrade/rollback and policy fingerprint changes require qualification.
- Internal artifact ID, output filename and export profile remain design choices.

## Published references

- [Processing](../../../dev/processing.md)
- [Storage](../../../dev/storage.md)
- [Canonical lifecycle](../../../dev/canonical-record-lifecycle.md)
- [Artifact export](../../../dev/artifact-export.md)
- [Managed import](../../../dev/dataframe-import.md)
- [Architecture boundaries](../../../BOUNDARIES.md)

## Confirmed scope refinement

The earlier IP/hash-only scope is superseded by the
[network amendment](network-routing-amendment.md), which adds URL/domain carriers. A reusable
multi-type gate is the working direction, with one scalar value per cell.
New name-update behavior must be selected by artifact configuration, not a
hardcoded aggregate artifact name. No backfill or cross-table live aggregation
is required. Managed import of aggregate files is required; adding a sink alone
does not register an import recognition/mapping/authority contract.

Attribution uses configured regex markers and the normalized full match nearest
before an IOC. `source.label` is a value provider, not the marker configuration.

## Implementation approach for review

The following are proposals, not implementation authorization:

1. Normalize old `when-type` and new `when-types` into one internal type gate.
   Reject simultaneous declarations and invalid/empty lists rather than silently
   selecting one. Retain one scalar cell per classified indicator and use the
   same gate in ordinary mapping and processed import. Include its semantics in
   processing/export fingerprints; account for compatibility of existing plans.
2. Represent duplicate handling as explicit artifact policy, with existing
   keep-first as the compatibility default. Aggregate selects replacement of
   `name` by a later eligible nonmissing value, without changing its identity.
   Avoid artifact-name checks and a general expression language.
3. Reuse canonical mutation primitives where practical; do not route ordinary
   document ingest through the entire managed-import delivery workflow. Update
   public row, lifecycle, revision/projection work and recovery evidence coherently.
4. Treat source marker expansion as shared input policy: it affects attribution
   for every artifact receiving those observations. New short FSTEK patterns
   must not partially match inside a longer legacy marker and steal attribution.
   Test token boundaries, punctuation, whitespace/NBSP and nearest-marker choice.
5. Register an explicit versioned aggregate import contract. Review whether the
   new `name` field is recognized as attribution/metadata by processed import,
   and preserve the ordinary missing-label rule without silently overriding the
   existing import distinction between ABSENT, NULL and VALUE.

### Accepted behavior to implement and remaining design decisions

- Implement accepted durable-registration priority with unchanged retry/restart
  identity; choose persistence/admission mechanics and legacy recovery handling.
- Implement accepted last-nonempty-label and corresponding-occurrence selection
  for aggregate. Current global batch keep-first must not discard this evidence
  early; preserve existing artifacts' behavior.
- Confirm export eligibility on a name-only mutation, even without a new IOC.
- Implement accepted per-field empty-value policy: preserve existing name for
  ABSENT/NULL/empty/whitespace-only input; insert NULL when unnamed. No aggregate
  name-clearing operation in this scope. Keep import tri-state information intact.
- Specify bare IPv4 versus IP-with-port/path routing, and whether imported
  compound IP+hash rows are valid; the document path emits one IOC per row.
- Choose profile/filename/default activation, then qualify active DB admission
  and rollback. Starting empty does not eliminate these transition checks.

## Current ingestion concurrency — code review clarification

The normal daemon document poller invokes its handler synchronously and processes
successful first attempts sequentially. `IocConfigPreflight` rejects ingestion
concurrency other than 1. Simultaneous file arrival does not itself start parallel
extractions; max-messages-per-poll is not a worker count. The earlier slow-A/fast-B
example must not be described as the normal first-attempt daemon flow.

However, `FileSourceMessageHandler` schedules failed attempts on its own retry
executor when backoff is positive and max-attempts exceeds 1 (classpath defaults:
5 seconds and 3 attempts). After scheduling A, the poller can process B; A's
retry can overlap B. `IngestionService` guards by source content key, not one
shared key for every document, so distinct contents are not mutually excluded.
Even without overlap, B may complete before a delayed retry of A.

Managed import drains one durable global import lane, with its own coordinator
and executor. This is not a shared global processing lane with document ingest.
Therefore arrival order and successful canonical mutation order are not one
contract across retries and the two input paths. Define latest for the actual
supported paths; do not add a general parallel document engine for this feature.
These conclusions are from source inspection, not a reproduced runtime race.
