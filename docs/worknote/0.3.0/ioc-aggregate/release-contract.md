---
title: "DATA-AGGREGATE-01 — Draft release contract"
version: "0.3.0"
status: "P0-P2 foundation implemented; activation pending"
document_type: "Draft release contract"
source_of_truth: false
language: "en"
---

# DATA-AGGREGATE-01 — draft release contract

## Scope

Requested addition to 0.3.0 under R030-DATA: one new configured IOC output
artifact with the public layout and update rule in [discovery](discovery.md).
The user authorized discovery, documentation and technical design. Initial scope includes IPv4, hashes, URLs and clean domains, empty-start and
managed aggregate import. See the [network amendment](network-routing-amendment.md).
Per-artifact mapping/update configurability is required. Missing new attribution
preserves an existing name. Extend shared section markers to recognize the five
observed label forms while retaining existing marker support. The accepted P0
ordering contract and P1/P2 policy/admission foundations are implemented. The
[technical design](technical-design.md) remains the contract for P3–P7;
aggregate generation, activation and release-blocking disposition are not yet settled.

## Compatibility requirements

- Preserve existing artifacts, keys, output mappings and keep-first behavior.
  Shared section-marker expansion is an explicitly requested attribution change
  and must be tested across affected artifacts.
- Preserve canonical SQLite authority, failure-policy checkpoint, lifecycle
  deadlines, immutable slice integrity and ledger-based recovery.
- No implicit import of the local sample and no automatic historical migration.
- No new input types, transport family, shared-library extraction or blanket
  change to all artifacts is implied.
- If the configuration DSL grows, retain existing single-type configurations
  and qualify fingerprints and processed-import behavior.
- Determine rollback behavior after a newer binary/configuration adds the
  artifact or mapping policy before claiming upgrade support.

## Completion criteria

Resolve blocking discovery questions, publish durable decisions where needed,
implement agreed slices, and satisfy the [verification matrix](verification-matrix.md).
Update affected published capability/configuration/operator documentation and
release notes in the same implementation changes. Accepted ADRs remain append-only.
An ADR is warranted for changed canonical update/order semantics, rather than
merely for adding a CSV column layout.

R030-TEST, R030-DOC, R030-REL and affected module quality checks apply. Cost and
release-date impact remain unestimated pending the persistence/recovery and
input-contract design. Ordering behavior is accepted: later durable registration
wins without priority changes on retries or restart. Within a document, aggregate
selects the last nonempty label and its corresponding occurrence. Empty-name
inputs preserve existing names; unnamed new rows store NULL.
Existing TTL/import closure does not constitute aggregate qualification.
