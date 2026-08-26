---
title: "DATA-IMPORT-01 — release contract"
version: "0.3.0"
status: "Approved architecture baseline"
document_type: "Release contract"
source_of_truth: false
language: "en"
---

# DATA-IMPORT-01 — release contract

## 1. Decision requested

Approve DATA-IMPORT-01 as an isolated scope change for the 0.3.0 candidate and
approve the architecture described in [architecture-project.md](architecture-project.md).
Approval authorizes implementation slices, schema migrations and the published
documentation changes listed in [implementation-plan.md](implementation-plan.md).
It does not treat this worknote as runtime authority and does not amend accepted
ADRs in place.

The release goal is:

> A daemon can claim a complete CSV delivery from a configured local directory
> or SMB share, identify exactly one declared dataframe contract without using
> the filename, and atomically patch or create active canonical records while
> preserving lifecycle, compound-row, external-slot and recovery invariants.

## 2. Mandatory product outcome

The accepted business decisions I-01 through I-41 in
[discovery.md](discovery.md) are normative input to this project. The following
statements condense them into release invariants; they do not replace the full
decision log.

1. One stabilized CSV occurrence is one delivery. A later occurrence is a new
   delivery even when its bytes are identical.
2. Recognition produces exactly one configured source contract. Zero or
   multiple matches reject the delivery before canonical writes.
3. Column order, source filename and non-semantic row order do not identify a
   contract. Declared headers, aliases, dialect and mapping do.
4. The default mode is `as-is` with `target-only` routing. `processed` and
   compatible related-artifact routing are explicit contract choices.
5. A delivery is a present-row patch/upsert over active records. Missing source
   rows never delete, expire, renew or modify local rows.
6. Incoming cells retain `ABSENT`, `NULL` and `VALUE` until merge. Merge policy
   is resolved source default, artifact override, then column override.
7. The default merge is `fill-missing`; supported alternatives are
   `keep-existing`, `replace-non-null`, `authoritative` and `reject-conflict`.
8. Historical records never participate in matching or merge.
9. Each logical row and every deterministic fan-out branch are accepted or
   rejected together. The entire accepted write set is committed in one
   dataframe-DB transaction across all affected artifacts.
10. Duplicate logical keys use configured deterministic `coalesce` or
    `keep-first`; apply-in-source-order is forbidden.
11. A compound record remains one row within one artifact. There is no
    cross-artifact relationship store. Repeated scalar rows express
    one-to-many data.
12. Matching returns zero, exactly one or multiple active lifecycles. Multiple
    matches and conflicting stable identifying fields reject the logical row;
    V1 has no conflict-resolution workflow.
13. A requested external slot is scoped to `(profile, primary artifact)`.
    Existing survivor assignments are preserved by default. A free request is
    exact; an occupied request falls back to the smallest free positive slot.
    `address_blacklist` has no external slot.
14. Public `time_first_seen` and `time_last_seen` are ordinary imported fields
    and never become lifecycle authority.
15. Unchanged accepted observations may renew TTL under a configured flag,
    enabled by default. TTL-only and no-op confirmations do not create an
    immutable export slice.
16. A public mutation advances each affected artifact revision once per
    delivery, not once per row, and uses the existing quiet/max-cap export
    scheduler.
17. Row failures continue under default `accept-valid`; structural, contract,
    ownership or consistency failures reject the whole delivery. Strict
    `reject-delivery` is configurable.
18. Only a private immutable snapshot obtained after a proven claim may be
    parsed. WatchService and SMB CHANGE_NOTIFY are latency hints; durable state
    and full reconciliation are correctness authorities.
19. Claimed deliveries apply in one durable global sequence. Retry of the head
    never permits a later sequence to overtake it.
20. A local terminal source and safe report are retained as one protected unit.
    Defaults are 30 days for success and 90 days for partial/rejected outcomes.
21. An SMB source becomes eligible only after its operator-provisioned private
    namespace passes the positive capability gate. Expired remote
    terminal/quarantine remnants are purged by exact token before local
    evidence, and the terminal ledger row remains the last retry authority.
    Replay has no remote source occurrence.

## 3. Scope

### 3.1 In scope

- daemon import from dedicated local and SMB sources;
- polling correctness backstop and optional local/SMB change hints;
- declarative source contracts, exact-one recognition and strict startup
  validation;
- `as-is` and `processed` mapping modes;
- target-only and declared related-artifact fan-out;
- disk-backed streaming validation, mapping, deduplication and planning;
- active-only matching, field merge, lifecycle confirmation and provenance;
- preferred immutable-export slots with stable-survivor behavior;
- one cross-artifact dataframe transaction and an idempotent promotion receipt;
- durable ordering, retry, restart recovery and terminal disposition;
- operator-provisioned SMB service namespace, per-source capability admission
  and bounded source-side terminal retention;
- transport-neutral source readiness, managed-object identity, immutable local
  snapshot and retention orchestration reused by local and SMB flows;
- advisory CLI validation/preview plus read-only status and health;
- aggregate diagnostics, metrics and protected per-delivery reports;
- bounded resource controls and performance/load qualification;
- required refactoring of shared write, projection, slot and ownership
  mechanisms where current behavior cannot satisfy these invariants.

### 3.2 Explicitly out of scope

- XLSX, JSON, STIX, OpenIOC or archive-container input;
- filename-based routing, heuristic charset/dialect guessing or scoring among
  ambiguous contracts;
- automatic deletion based on absence from a delivery;
- matching against history or revival of a closed lifecycle;
- manual merge/conflict-resolution UI;
- cross-artifact entity/correlation graphs;
- collections encoded inside one cell;
- per-file signatures or a public-key trust model;
- manual queue deletion, reordering, skipping or mutation;
- distributed workers, an external broker, event sourcing or cross-host leases;
- dynamically hot-reloaded contracts;
- changing the public artifact schemas except values already declared by their
  mappings;
- changing lifecycle semantics established by ADR-0020 through ADR-0023.

## 4. Compatibility contract

- Import is disabled by default for classpath and upgrade configurations.
- Existing extraction, oneshot operation, mutable projection, export, publish,
  fetch and lifecycle scheduling continue when import is disabled.
- Fresh production configuration must opt into sources and contracts
  explicitly; no implicit default inbox grants write authority.
- Existing dataframe databases undergo a controlled identity/match-index and
  slot-registry migration before import readiness. Migration failure is
  fail-closed and leaves intake stopped.
- A delivery pins contract ID, version and a canonical fingerprint. Restart
  continues with the pinned contract even after configuration changes.
- Existing CSV export formatting remains byte-compatible unless a separately
  approved projection change is required.
- No adapter-to-adapter dependency or framework dependency in core is allowed.
- Application services must not branch on SMB to perform capability, snapshot
  or retention policy. SMB paths/status/session types stay in its adapter.
- Managed-object identity and local immutable snapshot publication have one
  implementation contract across local and SMB sources; candidate tokens remain
  claim evidence and are not cleanup locators.
- Refactoring preserves the current managed-object token bytes and resolves
  existing local/SMB snapshot-reference versions until their durable deliveries
  are no longer recoverable; no in-place evidence rewrite is allowed.

## 5. Definition of ready

Implementation may begin after all of the following are approved:

- this scope and its explicit exclusions;
- the target module/port model;
- the canonical record-key and match-alias migration;
- the range-based sparse slot allocator extension;
- the local and SMB immutable-claim contract;
- service/dataframe schema ownership and recovery state machine;
- the implementation slice order and rollback points;
- the qualification matrix and reference resource envelope.

The implementation environment must also provide a real SMB qualification
target. Mock-only evidence cannot prove rename/share-mode behavior.

## 6. Definition of done

DATA-IMPORT-01 is complete only when:

1. I-01 through I-41 are traceable to executable tests or documented operator
   evidence.
2. All accepted rows and artifacts commit atomically, including crash-injection
   evidence around the dataframe commit boundary.
3. Retry/restart cannot double-apply a delivery or reorder the durable queue.
4. Local and SMB claims pass the ownership and immutable-snapshot contract
   corpus.
5. Matching, tri-state merge, compound records, fan-out and requested slots
   pass contract/TCK/golden scenarios.
6. A million-row reference import stays inside the approved heap, disk and
   latency envelope and has indexed query-plan evidence.
7. Ordinary ingest, lifecycle reconciliation, export and import do not starve
   one another under contention.
8. Diagnostics, health, reports and retention expose no raw IOC, path,
   credential or digest data outside protected artifacts.
9. SMB qualification uses separate producer/service identities and proves
   namespace admission, no-replace claim, terminal disposition, remote-first
   exact purge, producer denial and crash-safe ledger-last cleanup.
10. Architecture/contract tests prove one managed-object ID formula, one local
    immutable-snapshot implementation contract, transport-neutral readiness and
    retention orchestration, and no adapter-to-adapter dependency.
11. Published ADR, capability, module, configuration, security, operations and
   release documentation match the implementation.
12. Packaging upgrade, rollback and fresh-install qualification pass.
13. `make verify` passes on the final committed HEAD and `make context` reports
    fresh evidence.

## 7. Release accounting

This work is not silently absorbed into the already implemented TTL slices.
After approval, the release matrix must register a separate blocking work item
or an explicitly added sub-goal under `R030-DATA`. Until that registration and
the Definition of Done are complete, the feature and the affected release goal
remain in progress.
