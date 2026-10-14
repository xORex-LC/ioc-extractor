---
title: "DATA-AGGREGATE-01 — Release contract"
version: "0.3.0"
status: "P0-P7 implemented and qualified"
document_type: "Release contract"
source_of_truth: false
language: "en"
---

# DATA-AGGREGATE-01 — release contract

## Scope

Requested addition to 0.3.0 under R030-DATA: one new configured IOC output
artifact with the public layout and update rule in [discovery](discovery.md).
The user authorized discovery, documentation and technical design. Initial scope includes IPv4, hashes, URLs and clean domains, empty-start and
managed aggregate import. See the [network amendment](network-routing-amendment.md).
Per-artifact mapping/update configurability is required. Missing new attribution
preserves an existing name. Extend shared section markers to recognize the five
observed label forms while retaining existing marker support. The accepted P0
ordering contract and P1–P7 policy/admission/preparation/mutation,
import/export, transition and qualification slices are complete. The
[technical design](technical-design.md) defines the implementation boundary and
the [evidence ledger](evidence.md) records qualification. Aggregate generation
is enabled in the shipping preset; managed
import remains disabled until an operator supplies a validated source.

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
- Rollback after schema v11 requires coordinated restoration of the matching
  binary, configuration, both databases and service-owned files. Binary-only
  downgrade or manual schema stripping is unsupported.

## Completion criteria

Resolve blocking discovery questions, publish durable decisions where needed,
implement agreed slices, and satisfy the [verification matrix](verification-matrix.md).
Update affected published capability/configuration/operator documentation and
release notes in the same implementation changes. Accepted ADRs remain append-only.
An ADR is warranted for changed canonical update/order semantics, rather than
merely for adding a CSV column layout.

R030-TEST, R030-DOC, R030-REL and affected module quality checks apply. The
aggregate import/export, activation/rollback and input contracts are qualified.
Ordering behavior is accepted:
later durable registration wins without priority changes on retries or restart.
Within a document, aggregate
selects the last nonempty label and its corresponding occurrence. Empty-name
inputs preserve existing names; unnamed new rows store NULL.
Existing TTL/import closure did not substitute for the aggregate-specific
qualification recorded in the evidence ledger.
