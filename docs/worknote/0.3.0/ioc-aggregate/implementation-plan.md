---
title: "DATA-AGGREGATE-01 — implementation plan"
version: "0.3.0"
status: "P0-P6 implemented; P7 qualification planned"
document_type: "Implementation plan"
source_of_truth: false
language: "en"
---

# Implementation plan

These slices implement the [technical design](technical-design.md). P0–P6 are
implemented: the aggregate preset, import/export integration and transition
operations now build on the P0–P4 foundations. The deterministic P5–P6 quality
checkpoint is complete; P7 remains the representative performance and final
publication checkpoint. Q-03 supports both service-ledger
modes; the Q-07 rollback boundary is coordinated restoration rather than binary
downgrade.

| Slice | Changes and ownership | Exit condition |
|---|---|---|
| P0 — contracts and risk probes | Decide wire/import/export/storage-mode choices; publish proposed ADR with final ownership. Specify local producer handoff, replacement detection, snapshot ownership, oneshot unresolved-registration handling and operator activation procedure. | Reviewed interfaces, recovery state transitions and independently specified consumer fixtures; targeted probes demonstrate claim adoption, cached-candidate semantics and name-only export propagation. No feature activation. |
| P1 — policy and configuration | Application immutable policy/order types and pure selector/field strategies; bootstrap semantic compiler; shared CSV type gate; explicit semantic fingerprints. | Old when-type remains valid; new when-types and invalid combinations tested; no framework leaks; legacy defaults equivalent. |
| P2 — durable admission | Dataframe registration/namespace migration; service admission journal migration; document prehash claim adoption; import delivery reference; oneshot decorator; terminal handshake and bounded retention. | Crash/restart at every local-transaction boundary retains priority; missing resume rank fails; registration never requires nested cross-DB locks; dry-run has no registration side effects. |
| P3 — occurrence-preserving preparation | Carry attribution index through dedup/classification; map and select per artifact; extend marker patterns with overlap handling; version confirmation receipt preparation payload. | Last labeled occurrence is selected intact by mapped key; classification remains once per existing unique key; existing artifacts retain their behavior except requested marker recognition. |
| P4 — canonical mutation | Adapter collaborators apply pure field decision with provenance; shared lifecycle/disabled-mode support; public versus metadata-only outcomes; revision, projection, recovery/event propagation. | Newer names win independently of completion order; same-value input advances origin only; empty input preserves; atomic failure rolls back row and origin; TTL/history invariants pass. |
| P5 — import/export integration | Explicit aggregate contract/source binding, configurable last-nonempty CSV staging reduction, policy authority composition, pinned stage fingerprint; profile/config template only after choices resolved. | Target-only import, processed/as-is validation, duplicate behavior and replay verified; name-only changes delivered according to Q-01; mutable and immutable consumer fixtures match. |
| P6 — transition and operations | Legacy-work drain barrier; schema and receipt compatibility; new-artifact admission with active lifecycle; safe diagnostics/status, registration cleanup, coordinated backup/restore guide. | Fresh/upgrade/restart/restore evidence, no backfill; stale preparation never silently reinterpreted; cleanup cannot re-rank recovery; unresolved CLI state visible. |
| P7 — qualification and publication | Published ADR/capability/reference/operator docs, release notes; focused and full checks, analyzer inspection, representative performance comparison. | Final-worktree evidence meets verification matrix; pending external checks explicitly scoped; no claim that old TTL/import evidence qualifies this feature. |

P1 establishes policy contracts used by P2/P3; P4 joins their outputs. P5 depends
on canonical outcomes and P6 covers the combined recovery behavior. Keep each
slice reviewable and configuration-disabled until its dependencies are complete.
Do not merge unfinished behavior into an enabled shipping preset.

## Execution checkpoint

| Slice | Commit | Implemented result |
|---|---|---|
| P0 | `0ca0fda6` | Proposed ADR-0030 fixes the single dataframe-owned order authority, file/JDBC recovery boundary, producer handoff and terminal-reference rules. No activation. |
| P1 | `c215c103` | Configurable `when-types`/`when` mapping gates, compatibility with `when-type`, semantic fingerprinting and pure occurrence/write policy contracts. |
| P2 | `f27740e6`, `272fbecc` | Dataframe/service schema v10, JDBC registration and recovery stores, durable file/JDBC document journals, import references, oneshot decorator, pre-hash claim/seal recovery and crash-boundary tests. |
| P3 | `008b2b5d`, `783d6b69` | Occurrences survive batch deduplication while classification remains once per normalized key; opt-in whole-row last-nonempty selection, marker overlap/NBSP handling and strict identity validation preserve legacy artifact behavior. |
| P4 | `facee3d0` | Both lifecycle and compatibility JDBC paths apply latest-registered mutable fields atomically, persist active/history provenance and receipt positions, distinguish public from metadata-only changes and propagate projection/recovery events. |
| P5 | `72f4daa1`, `202fd03c` | The shipped five-column aggregate artifact and isolated export profile are active; target-only managed import adds exact carrier validation, last-nonempty duplicate reduction, pinned policy metadata and source-label binding. |
| P6 | `86bb0373` | Daemon file/JDBC ledgers and managed import share durable dataframe order, service schema v11 proves new import reservations, startup blocks legacy unranked work, retention/health expose and preserve unresolved authority, and v0.2 identity overlays remain boundedly compatible. |
| P5–P6 hardening | `0ecacac0`–`6f1d9b4d` | Runtime contract fixes, ordered recovery and retention boundaries, strict import validation, exact coverage qualification, PMD remediation and the reviewed CPD improvement close the deterministic implementation checkpoint. |

The ordered occurrence and mutation mechanisms remain opt-in per artifact;
existing four artifacts keep their legacy policies. The shipping aggregate
preset selects them for `name`, starts empty without backfill and is covered by
the P5 import/export and P6 recovery paths. Exact deterministic gates and
analyzer review are recorded in the evidence ledger. P7 must still record the
representative performance comparison and any provisioned external evidence
before release qualification is claimed.

## Responsibilities and review boundaries

- Application owns business policy and use-case coordination; IOC domain remains
  free of file/admission/storage concepts. Adapters own transport, SQL and CSV.
- New collaborators absorb cohesive responsibilities; existing large orchestrators
  delegate. Review dependency direction and behavior, not arbitrary class-size
  thresholds or mechanical service/implementation pairs.
- Preserve published concurrency API/tag/artifact; no library release is needed.
- P0–P6 implementation and logical commits are owner-authorized. Record each
  slice against its actual tested commit in the evidence ledger.

## Verification workflow

Use focused tests per slice; production changes require make verify and
make pmd-analysis on the final worktree. Review raw SpotBugs, CPD and PMD findings,
not only exit codes. Run the PMD watchlist where resource/exception ownership or
size changes trigger repository rules. Never relax quality ratchets for this work.

Concurrency tests use deterministic barriers/latches, a containing JUnit timeout,
and bounded worker cleanup. Test actual conflicting observations and crash windows,
not just comparator methods. Use isolated databases for migrations and restore
checks. Provisioned external results identify target and cleanup without secrets;
offline skips are not stand qualification. Run documentation/link checks for this
bundle and published docs. Record evidence against the tested commit/worktree.
