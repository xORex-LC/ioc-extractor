---
title: "DATA-AGGREGATE-01 — Worknote bundle index"
version: "0.3.0"
status: "P0-P6 implemented; final qualification pending"
document_type: "Worknote bundle index"
source_of_truth: false
language: "en"
---

# DATA-AGGREGATE-01 — IOC aggregate output

This bundle records the requested additional output artifact for release 0.3.0.
Documentation work was authorized on 2026-09-21; P0–P6 implementation was
authorized subsequently and is now implemented. The shipping preset includes the
aggregate artifact and isolated export profile, while managed import remains
operator-disabled. Final P7 exact-HEAD qualification and release publication are
still pending. Status is recorded in the evidence ledger. The work-item ID is
local to this release bundle.

These mutable worknotes do not replace accepted ADRs, capability documentation
or operator guides. New documents use English according to repository policy.

## Reading order

1. [Discovery](discovery.md): confirmed requirements and decision history.
2. [Architecture assessment](architecture-project.md): current mechanisms and gaps.
3. [Draft release contract](release-contract.md): scope and compatibility boundaries.
4. [Technical design](technical-design.md): component ownership, ordering, recovery and activation.
5. [Dependency assessment](library-options.md): reuse and alternatives.
6. [Implementation plan](implementation-plan.md): proposed slices and exit conditions.
7. [Verification matrix](verification-matrix.md): intended behavioral evidence.
8. [Evidence](evidence.md): inspected baseline and actual checks.

## Current disposition

- New standalone artifact; not a union/view over existing artifact tables.
- Public columns: `name;ip_address;url_match;host_match;hash`.
- Matching compares all public fields except `name`; a later observation updates
  `name` on the same matching row; missing new attribution preserves the old name.
- Initial scope includes IPv4, hashes, URLs and clean domains. One carrier per row;
  see the [network amendment](network-routing-amendment.md).
- Start empty, without historical backfill. Support managed import of the new
  schema through explicit existing import contracts.
- Mapping and duplicate/update rules must be configurable per artifact.
  Extend section markers for all five observed sample label forms.
  Later registration wins; retry/restart preserve priority. Within a document,
  retain the last nonempty label and its corresponding occurrence.
  Empty-name, import/publication and rollout policies are recorded in the
  question table in the technical design.
- Earlier TTL/import verification remains evidence for its original scope;
  it does not qualify this feature. No full-release completion is claimed.
- P0–P6 provide the accepted ordering contract, configurable mapping-policy
  seams, durable admission, occurrence-preserving preparation, atomic ordered
  field mutation, aggregate import/export and transition operations. No historical
  backfill occurs; production rollout follows the coordinated backup/restore
  boundary in the operator guide.
