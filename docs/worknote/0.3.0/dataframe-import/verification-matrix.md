---
title: "DATA-IMPORT-01 — verification matrix"
version: "0.3.0"
status: "Approved; execution in progress"
document_type: "Verification plan"
source_of_truth: false
language: "en"
---

# DATA-IMPORT-01 — verification matrix

## 1. Evidence layers

| Layer | Purpose |
|---|---|
| pure unit/property tests | contract compilation, tri-state merge, deterministic dedup, identity and policy algebra |
| application port TCK | ledger transitions/order/idempotency and canonical import atomicity |
| JDBC integration | migrations, active matching, set-based plans, lifecycle, revisions, slots, receipts and crash injection |
| adapter contract corpus | CSV encoding/dialect and local/SMB ownership behavior |
| bootstrap tests | strict config, startup barrier, events, scheduling, health and shutdown |
| synthetic golden E2E | local delivery through terminal report/export, all artifact families and modes |
| packaged qualification | permissions, service lifecycle, real SMB, upgrade/rollback |
| load/soak evidence | bounded resources, latency, query plans, fairness and recovery backlog |

## 2. Business-decision traceability

This table is the P0 traceability gate. It contains every decision ID from
I-01 through I-41 exactly once or in one explicit grouped test obligation.

| Decisions | Required executable evidence |
|---|---|
| I-01, I-06, I-07, I-20, I-25, I-40 | occurrence identity, identical re-delivery, pinned retry, replay causal link, immutable claim |
| I-02, I-09, I-19, I-34, I-37 | exact-one recognition, authority ceiling, target/related routing and fan-out atomicity |
| I-03, I-04, I-05, I-21, I-24 | active-only present-row patch, all merge policies, ABSENT/NULL/VALUE, no implicit priority |
| I-08, I-26, I-27, I-35 | accept-valid/strict outcome, safe report, paired retention, formula rejection |
| I-10, I-23, I-28, I-36 | cross-artifact transaction, durable head order, bounded staging, one batch revision |
| I-11, I-12, I-13, I-14 | as-is/processed ownership, imported public time fields without lifecycle authority |
| I-15, I-30, I-31, I-32, I-33 | deterministic duplicates, compound rows, zero/one/multi match, identity conflict, repeated scalar identity |
| I-16, I-17, I-18, I-41 | slot scope, exact/fallback, duplicate conflict group, survivor mismatch policy |
| I-22, I-29 | strict charset/dialect and restart-pinned catalog generation |
| I-38, I-39 | advisory preview and read-only status with no reservation/ledger mutation |

## 3. Contract and mapping matrix

Test at least:

- column reorder, alias rename, ignored column and absent optional column;
- duplicate, unexpected, ambiguous and missing required headers;
- UTF-8 with/without allowed BOM, malformed bytes and disallowed charset;
- quoted delimiter/newline, escaped quote, empty record and maximum-size edge;
- zero, exact-one and multiple structural contract matches;
- every `ABSENT|NULL|VALUE` by every merge policy for null/non-null existing
  state;
- stable-field conflict under every ordinary merge policy;
- coalesce permutation/property tests and keep-first row-number behavior;
- target-only and multi-artifact branch failure propagation;
- formula prefixes including leading whitespace and configured safe columns;
- as-is explicit transforms only and processed derived-field ownership;
- public time-field import with no lifecycle deadline change beyond normal
  confirmation.

## 4. Identity and lifecycle matrix

- new simple and compound row key;
- partial key exact-one enrichment;
- scalar key matching multiple compound rows;
- repeated `(A,B)` and `(A,C)` composite rows;
- hash-role and address-role match definitions;
- alias update after allowed mutable-field change;
- history ignored at all match paths;
- equality at `_valid_until_epoch_ms == asOf` treated as expired;
- post-expiry observation creates a new canonical/lifecycle identity;
- identity migration preserves canonical/lifecycle IDs and detects collisions;
- ordinary ingest after import uses the same aliases and does not duplicate an
  imported row;
- accepted no-op with renewal enabled/disabled;
- insert, update, clear, TTL-only and no-op observation outcomes.

## 5. Atomicity and recovery matrix

Inject failure before/after every boundary:

- claim rename;
- sequence allocation;
- `.part` copy, file force and snapshot publish;
- contract pin;
- staging batch, seal and ledger adoption;
- ID range reservation;
- each promotion phase and transaction commit;
- service update after commit;
- projection convergence;
- report temporary write/publish;
- terminal directory publish;
- local/remote disposition and terminal ledger transition.

Assertions:

- canonical state is entirely before or after the delivery;
- one logical row never has a partial branch;
- a dataframe receipt prevents duplicate apply;
- no receipt after rollback;
- recovery moves forward from durable evidence;
- minimum nonterminal sequence is the only promotable delivery;
- retry and capacity pause never allow overtaking;
- terminal replay has a new delivery ID/sequence and preserves causal link.

## 6. Slot matrix

- exact request below, at and above high water;
- occupied request fallback to smallest positive free slot;
- request `1`, a released hole, and `1_000_000_000`;
- multiple new rows with same request conflict as one group;
- survivor same request, mismatch preserve and mismatch reject;
- expired slot release only at eligible reconciliation;
- survivor stability and no compaction;
- range split at start/middle/end and adjacent-range merge;
- migration from enumerated free rows preserves every assignment;
- rollback restores assignments/ranges with canonical rows;
- address blacklist rejects any external-slot mapping.

## 7. Source ownership matrix

### 7.1 Local

- atomic rename success and unsupported atomic move;
- source/destination collision without replacement;
- symlink, hard-link policy, directory, device and path escape;
- overlapping configured roots rejected at startup;
- producer keeps a handle open or attempts rewrite after claim;
- duplicate WatchService event, overflow and no event;
- disk full before/after copy and before snapshot publish;
- orphan processing object adoption after restart.

### 7.2 SMB

- regular-file server-side rename and destination collision;
- concurrent producer write/delete and relevant SMB share/access modes;
- rename success then download/session failure;
- `.part` resume/restart behavior and remote orphan adoption;
- reconnect, notification loss/duplication/overflow;
- archive/quarantine retry after canonical commit;
- credentials cannot cross source authority boundaries;
- full listing remains correct with CHANGE_NOTIFY disabled.

At least one real Samba deployment and each supported production server family
must execute the contract suite.

## 8. Security and leakage matrix

- raw IOC, CSV cell, filename, path, digest and credential canaries never appear
  in logs, events, health or unprotected status;
- report contains only row number/code/safe bounded detail;
- CSV formula-dangerous values reject without silent mutation;
- parser handles decompression-free CSV only and honors byte/field/row limits;
- config cannot reference unknown transform/provider/predicate/match definition;
- source authority ceiling prevents artifact/column/policy escalation;
- health and status queries use bounded indexed aggregates;
- protected file permissions and packaging ownership are verified.

## 9. Performance qualification

Reference datasets:

- small functional corpus;
- 100,000-row baseline aligned with existing lifecycle load evidence;
- 1,000,000-row mixed insert/update/no-op/conflict delivery;
- sparse-slot corpus including very high requested IDs;
- compound/multi-match high-collision corpus.

Record for each environment:

- hardware, filesystem, JVM, heap, SQLite and configuration;
- snapshot/stage sizes and peak disk usage;
- wall time by claim, parse/stage, promotion and finalization;
- peak/steady heap and GC behavior;
- writer wait and hold time;
- rows/second and public mutations/second;
- projection convergence and head completion latency;
- database WAL/checkpoint behavior;
- query plans and index cardinalities.

Acceptance requires:

- heap reaches a plateau independent of total CSV rows;
- no O(max requested slot) time or storage behavior;
- promotion plans scan staging and use indexed canonical/alias lookups;
- no per-row canonical lookup/commit pattern;
- later import/ordinary/lifecycle writers do not starve under a controlled
  contention run;
- retry/recovery backlog remains bounded and converges after injected failure;
- the approved 1M-row reference completes within explicitly recorded operator
  SLOs. Exact latency/heap thresholds are fixed after the first reproducible P4
  baseline, before P6 is accepted; they are not invented without measurements.

## 10. Build and documentation gates

Per slice, run the focused module/TCK/config/migration tests and documentation
checks appropriate to its scope. Before completion run:

- full Maven reactor `verify`;
- ArchUnit module/package rules;
- Enforcer and quality analyzers;
- synthetic golden E2E corpus;
- lifecycle/import smoke and reference load;
- packaging fresh-install, upgrade and rollback;
- rendered configuration/help/diagnostics checks;
- documentation convention/link checks;
- final `make context` freshness check on committed HEAD.

External or privileged evidence that is unavailable is recorded as a release
blocker/skip, never reported as a pass.
