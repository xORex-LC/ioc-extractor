---
title: "DATA-IMPORT-01 — implementation plan"
version: "0.3.0"
status: "Approved; P0-P8 implemented"
document_type: "Implementation plan"
source_of_truth: false
language: "en"
---

# DATA-IMPORT-01 — implementation plan

## 1. Execution rule

Architecture was approved on 2026-08-23. Each slice must leave
the reactor buildable, update affected documentation in the same change and
produce its own focused evidence. A later slice may not hide an unmet invariant
from an earlier gate.

Refactoring is intentional where the live mechanism cannot satisfy import
correctness. Unrelated cleanup is excluded.

## 2. Decision publication before code

Create one new append-only ADR that:

- supersedes ADR-0015 only for managed, contract-driven dataframe import while
  preserving its removal of hand-filled runtime lookup/seed behavior;
- defines delivery occurrence, exact-one contract, immutable snapshot,
  active-only patch/upsert and cross-artifact atomic promotion;
- establishes service-ledger plus dataframe-receipt recovery;
- extends ADR-0020 observation/lifecycle semantics for updates/clears/no-ops;
- extends ADR-0021 with preferred-slot assignment and range-based free space;
- preserves ADR-0022 revision-significant delivery and ADR-0023 bounded
  reconciliation semantics;
- records events as hints and global order as ledger authority;
- records identity/match aliases and migration constraints.

Do not edit accepted ADR text to retrofit this decision.

## 3. Implementation slices

### P0 — contract and boundary baseline

Status: **implemented and committed after working-tree verification**. See
[p0-evidence.md](p0-evidence.md). Committed-HEAD freshness is intentionally not
claimed because the full gate records the equivalent pre-commit tree; a fresh
gate is deferred to the next completed implementation slice.

Deliver:

- accepted ADR and registered release scope;
- framework-free import contract/model packages;
- source catalog compiler and fingerprint model;
- `ImportCell`, merge/match/duplicate/failure/slot policy values;
- driving/driven port interfaces and application TCK skeletons;
- ArchUnit and Maven boundary guards;
- dedicated `DataframeImportConfiguration` shape, disabled by default.

Gate:

- strict unknown-key and semantic collect-all configuration tests;
- no Spring/CSV/JDBC/SMBJ imports in core;
- all I-01..I-41 mapped to planned tests.

### P1 — integration-family preparatory refactors

Status: **implemented and committed with focused compatibility verification**.
See [p1-evidence.md](p1-evidence.md). The full reactor gate remains scheduled
after P2 so it covers the complete requested implementation span.

Deliver:

- mechanical `adapter-sink-csv` to `adapter-csv` rename;
- library-neutral dialect value; Commons CSV construction moves into adapter;
- strict decoder/parser boundary with streaming row callback/iterator;
- cursor-streamed mutable projection to temp plus atomic replace;
- reusable classifier extraction from `ClassifyIndicatorsStage`;
- neutral artifact-level canonical-change event;
- transport-neutral remote watch target;
- separate import bootstrap configuration.

Gate:

- existing export golden bytes unchanged;
- mutable projection heap no longer scales with active-row count;
- ordinary ingest/export/sync behavior and module dependency tree unchanged
  except deliberate names/contracts.

### P2 — canonical identity and mutation kernel

Status: **implemented and committed with focused compatibility verification**.
See [p2-evidence.md](p2-evidence.md). The final committed-HEAD reactor gate is
run after the documentation commit and reported by repository verification
evidence rather than embedded into this pre-gate note.

Deliver:

- versioned record-key and match-key definitions;
- dataframe v7 match-definition/alias migration and collision preflight;
- compound record-key migration for affected artifacts;
- shared `JdbcCanonicalMutationEngine` used by ordinary lifecycle writer;
- public update/clear/no-op mutation outcomes;
- set-based match-plan primitives;
- identity and canonical writer TCKs.

Gate:

- existing canonical/lifecycle IDs and public bytes preserved through migration;
- ordinary ingest maintains aliases and cannot create import-only duplicates;
- active-only zero/one/multi matching and expiry boundary tests pass;
- collision fixture fails before mutation and prevents readiness.

### P3 — sparse preferred export slots

Status: **implemented and committed with focused compatibility verification**.
See [p3-evidence.md](p3-evidence.md). The final committed-HEAD reactor gate is
scheduled after P4 so it covers the complete authorized implementation span.

Deliver:

- coalesced free-range migration;
- connection-scoped slot registry usable by export and import;
- exact requested slot, occupied fallback, duplicate request group and survivor
  mismatch policies;
- query-plan and extreme sparse-ID tests.

Gate:

- all ADR-0021 survivor/reuse tests remain green;
- slot `1_000_000_000` requires bounded rows/time;
- no assignment changes during migration;
- allocator is atomic with its owning canonical transaction.

### P4 — durable delivery ledger and workspace

Status: **implemented and committed with focused compatibility verification**.
See [p4-evidence.md](p4-evidence.md). The final committed-HEAD reactor gate is
run after the P4 documentation commit.

Deliver:

- service schema v9 ledger/transition/indexes and TCK implementation;
- CAS state machine, global sequence, retry schedule and head query;
- per-delivery SQLite staging adapter and sealed-stage protocol;
- strict recognition/mapping, tri-state cells, duplicates, fan-out and safe row
  error storage;
- hard limits and capacity watermarks.

Gate:

- deterministic results independent of harmless row/column order where the
  selected policy requires it;
- restart at every pre-promotion transition recovers or safely rebuilds;
- staging load passes low-heap 100k-row baseline before canonical writes exist.

### P5 — local managed intake

Status: **implemented and committed with focused compatibility verification**. See
[p5-evidence.md](p5-evidence.md). Runtime startup remains deliberately gated by
P7's shared recovery barrier; P5 delivers and qualifies the complete local
ownership/admission boundary without opening intake early.

Deliver:

- strict local ownership transfer and immutable snapshot store;
- source-keyed detection with polling backstop and optional watch hint;
- global sequence admission and snapshot-pinned nudge;
- protected processing/staging/terminal directory conventions;
- ordinary ingest migration to shared ownership primitive where compatible;
- no blocking retry sleeps.

Gate:

- symlink, path escape, collision, producer-open-handle, unsupported atomic
  move, duplicate/lost event and disk-full scenarios pass;
- no canonical write path is reachable without pinned snapshot evidence.

### P6 — atomic promotion

Status: **implemented and committed with affected-reactor verification**. See
[p6-evidence.md](p6-evidence.md). Composition into the daemon remains gated by
P7's shared recovery-before-intake barrier.

Deliver:

- `CanonicalImportWriter` in JDBC adapter;
- read-only sealed-stage attach/cursor;
- set-based active match, tri-state merge, fan-out rejection and failure-policy
  checkpoint;
- lifecycle/provenance/alias/slot/revision/projection changes in one transaction;
- `import_commit` and safe rejection evidence;
- fair same-process writer admission;
- post-commit deadline/projection/artifact hints.

Gate:

- failure injection at every SQL phase leaves all artifacts before or after,
  never partial;
- crash after commit but before service update resumes by receipt without
  reapplication;
- accepted row fan-out is all-or-none;
- TTL/no-op and public mutation revision semantics pass.

### P7 — recovery, finalization and operator UX

Status: **implemented with focused and affected-reactor verification**. See
[p7-evidence.md](p7-evidence.md). Full committed-HEAD verification follows P8
so the gate covers the requested P7/P8 span.

Deliver:

- startup recovery barrier shared with ordinary ingest;
- global lane drain and periodic reconcile;
- report generation and atomic terminal source/report unit;
- local archive/quarantine and bounded retention;
- validate/preview, aggregate status and replay use cases;
- health, metrics, logs and generated diagnostic catalog;
- graceful shutdown ordering.

Gate:

- crash matrix from claim through terminal completion passes;
- later sequence never overtakes retrying head;
- events may be lost/duplicated/rejected without correctness loss;
- status/health/report leakage tests pass.

### P8 — SMB managed intake

Status: **implemented with focused and live SMB verification**. See
[p8-evidence.md](p8-evidence.md). The approved live stand covers the repository
Samba contract; additional production server-family breadth remains a P9
packaging/release qualification obligation. Full committed-HEAD verification
follows the P8 commit and covers the requested P7/P8 span.

Deliver:

- SMB server-side ownership rename and private processing namespace;
- shared SMB session/pool extraction;
- durable local materialization and orphan adoption;
- transport-neutral CHANGE_NOTIFY integration;
- remote terminal disposition and reconcile.

Gate:

- real Debian/Samba and representative target-server contract corpus passes;
- concurrent writer/share mode, collision, disconnect and orphan scenarios
  prove fail-closed ownership;
- polling remains correct with notifications disabled/lost.

### P9 — performance, packaging and publication

Deliver:

- 100k and 1M reference load evidence with query plans and heap profile;
- contention/fairness evidence with ingest, lifecycle, projection and export;
- packaging dirs/config/upgrade/rollback and operational smoke tooling;
- published `docs/dev/dataframe-import.md`, operator guide and module READMEs;
- updates to architecture, modularization, boundaries, processing, storage,
  ingestion, export, event coordination, configuration, observability, security,
  threat model, known issues and release notes;
- final release matrix/evidence links.

Gate:

- packaged fresh install and 0.2.0 upgrade/rollback scenarios pass;
- full reactor and documentation gates pass on final committed HEAD;
- `make context` reports fresh verification.

## 4. Slice dependencies

```text
P0
 +--> P1 --> P2 --> P3 --+
 |                       |
 +----------> P4 --> P5 -+--> P6 --> P7 --> P9
                              |
                              +--> P8 ----+
```

P4 may develop against in-memory/fake promotion ports while P1–P3 refactor
shared mechanisms. P6 cannot begin until identity, slot and staging contracts
are stable. SMB P8 follows the proven local/runtime state machine so transport
uncertainty does not obscure canonical correctness.

## 5. Change discipline

- Keep production-code slices reviewable; do not combine schema migration,
  module rename and business promotion in one commit.
- Add characterization tests before altering existing writer/slot/projection
  behavior.
- Stage explicit paths and preserve unrelated working-tree changes.
- Each schema migration is append-only and tested from every supported prior
  version.
- Do not claim release completion from focused tests alone.
- Any changed public/config/operational behavior updates its durable docs in the
  same slice.

## 6. Stop conditions requiring renewed approval

Stop implementation and return to design if evidence requires any of:

- changing missing-row patch semantics into snapshot replacement;
- matching historical records;
- partial canonical commit/chunked promotion;
- survivor slot renumbering;
- a new public artifact schema;
- a cross-artifact relationship store;
- a broker/distributed coordinator;
- non-atomic default ownership claim;
- manual queue bypass;
- a reference maximum below the approved operator workload.
