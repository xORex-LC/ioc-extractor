---
title: "DATA-IMPORT-01 — SMB namespace and retention hardening"
version: "0.3.0"
status: "H1-H4 implemented; H5 external qualification pending"
document_type: "Implementation worknote"
source_of_truth: false
language: "en"
---

# DATA-IMPORT-01 — SMB namespace and retention hardening

This worknote turns
[ADR-0025](../../../ADR/0025-managed-import-smb-service-namespace-and-terminal-retention.md)
into reviewable implementation scopes. It records target behavior and evidence
requirements; current runtime behavior remains documented in capability and
operator docs until each implementation scope lands.

## 1. Problem statement

The P8 candidate proves SMB claim/materialization/disposition on Samba, but two
operational boundaries remain incomplete:

1. the adapter creates its private service directories on demand instead of
   requiring an operator-provisioned least-privilege namespace;
2. terminal/quarantine remote objects are outside the existing retention
   cleanup sequence and can accumulate indefinitely.

Replay exposes a related contract seam: a replay delivery comes from retained
local evidence and therefore has no remote processing object to dispose or
purge.

## 2. Current-to-target delta

| Concern | Candidate behavior | ADR-0025 target |
|---|---|---|
| service directories | adapter calls recursive create for processing, terminal and quarantine | processing, terminal, quarantine and probe are pre-created; runtime creation is forbidden |
| admission | first real claim discovers permission/share-mode failures | per-source positive capability gate runs before intake |
| ACL proof | directory creation is treated as sufficient operational access | application proves positive operations; operator proves producer denial |
| remote terminal retention | remote terminal/quarantine object is not reaped | exact regular-file purge precedes local cleanup |
| durable cleanup authority | service ledger knows terminal delivery but not remote purge completion | terminal ledger row remains until every cleanup step succeeds |
| replay | common finalization can request remote disposition | replay is explicitly source-detached and skips remote disposition/retention |
| managed-object ID | local and SMB adapters duplicate `SHA-256(deliveryId)` | one application-owned `ImportManagedObjectId` value/factory |
| local immutable snapshot | local and SMB lifecycle adapters duplicate hash/fsync/atomic-publish/purge mechanics | one import snapshot port and local-filesystem implementation serves every source transport |
| delete surface | generic share delete can accept a path | source-retention port accepts managed-object ID/outcome only; SMB adapter exposes exact regular-file semantics |

## 3. Trust boundary and data flow

```text
producer identity                       service identity
       |                                       |
       | publish completed candidate          | positive capability probe
       v                                       v
<source>/  ---------------- claim -------->  .ioc-managed-import/processing
   |                                            |
   | producer denied below boundary             | materialize immutable local snapshot
   |                                            v
   |                                  local snapshot -> stage -> dataframe DB
   |                                            |
   |                                 protected local source/report unit
   |                                            |
   +--------------------------------------------+ disposition
                                                v
                              remote terminal/ or quarantine/
                                                |
                                  retention eligibility + ledger
                                                v
                                  exact remote purge -> local cleanup
                                                |
                                                v
                                      CAS delete ledger row last
```

Trust boundaries:

- producer and service identities are distinct;
- SMB server/share policy is external operator-owned infrastructure;
- remote names and object metadata are partially trusted only after fixed-root,
  token and regular-file validation;
- the local protected terminal unit is the replay/audit authority;
- the service ledger is the cleanup retry authority until final CAS deletion.

## 4. Required contracts

### 4.0 Placement and reuse rules

Generalize only to the narrowest layer with more than one real consumer:

| Concern | Owner | Reused by | Must not contain |
|---|---|---|---|
| age/count selection semantics | existing `application.maintenance.RetentionPolicy` | import, export and ordinary housekeeping policies | SMB paths, import receipts or remote operations |
| keyed single-flight/serialization | existing `platform-concurrency` | source detection, capability, disposition and retention scheduling | source transport or import business states |
| managed-object ID | dataframe-import application model | local source, local terminal/snapshot store and SMB source adapter | filename, path, endpoint or candidate metadata |
| source readiness state/coordinator | dataframe-import application | local, SMB and future real source transports | SMB status codes, sessions or share paths |
| immutable local snapshot contract | dataframe-import application port plus one local-filesystem implementation | local and SMB source access | SMBJ handles or adapter-to-adapter calls |
| cleanup eligibility/order/retry | `DataframeImportRetentionService` | every managed-import source transport | `if SMB`, remote paths or protocol exceptions |
| source access/probe/disposition/purge | transport adapter behind application ports | one implementation per actual transport | canonical merge, ledger ordering or retention policy |
| SMB session pool, share primitives, exception mapping and watch infrastructure | `adapter-transport-smb` | ordinary sync and SMB managed import | application policy or local-filesystem persistence |
| SMB managed namespace/probe/claim/disposition/exact purge | `adapter-transport-smb` | SMB managed-source implementation only | generic sync-fetch mutation or application cleanup ordering |
| composition and health projection | `ioc-app` | all configured sources | business rules duplicated from application services |

This does not justify a universal mutable `FileTransport`: ordinary sync fetch
is intentionally read-only. Reuse occurs at managed-source capabilities, not by
adding rename/delete to a lowest-common-denominator file API.

### 4.1 Source capability

Introduce an application-owned, transport-neutral capability port/result with:

- source ID;
- stable phase enum;
- `READY`, `TRANSIENTLY_UNAVAILABLE` or `INCOMPATIBLE` disposition;
- stable diagnostic code and retry eligibility;
- no endpoint, credential, path, token or raw exception fields.

One application coordinator gates claims per source. It does not publish a
partially ready source catalog, branch on transport enums or stop unrelated
local/SMB sources. A composite driven adapter routes by source ID at the
composition boundary.

The SMB adapter implements the reserved empty-token probe defined by ADR-0025.
The probe must use the same no-replace rename and exact-delete primitives as
private disposition and retention, without requiring create-child access in the
producer root. Per-candidate evidence checks still guard the real
source-to-processing claim, and live qualification supplies a producer-created
fixture for that boundary. Startup recovery may inspect already claimed objects
before new intake, but no new claim is allowed until the source gate is ready.

### 4.2 Terminal source retention

Add a narrow application port equivalent to:

```text
purge(deliveryId, sourceId, managedObjectId, expectedOutcome) -> result
```

The exact Java API may use one immutable command value. The command must not
contain a path, URI, SMB status or adapter exception. Required result semantics:

- exact regular file deleted: completed;
- exact expected file already absent: completed idempotently;
- transient transport failure: retryable failure;
- wrong outcome location, object still in processing, invalid managed-object ID,
  non-regular object or contradictory evidence: fail-closed inconsistency.

The candidate token is not accepted by this port. A common
`ImportManagedObjectId` is derived from the delivery ID once and carries only a
closed token grammar. The SMB implementation builds a path only from a
validated source root, fixed terminal/quarantine segment and that ID. It never
deletes a directory, follows a link/reparse-like object, traverses a listing
result or accepts a wildcard.

### 4.3 Common immutable snapshot store

Extract local immutable snapshot publication from both source lifecycle
adapters. One application port and one local-filesystem implementation own:

- protected delivery directory creation;
- bounded byte transfer, SHA-256 and byte count;
- file and parent-directory force;
- no-replace atomic publication;
- reference resolution/verification;
- idempotent part/final snapshot purge.

The application-owned materialization use case coordinates source access and
this snapshot store. Local and SMB source adapters supply claimed bytes plus
before/after source evidence; the snapshot adapter owns local persistence. The
exact streaming handoff API is finalized in H1, but it must use
application/JDK types and introduce no adapter-to-adapter dependency.

### 4.4 Replay source detachment

Persist an application-level disposition/retention receipt (or an equivalently
explicit source-occurrence kind) so forward deliveries and replays do not share
ambiguous behavior. It expresses `no source remnant` or one managed terminal
object; it is not an SMB locator. A replay:

- creates a new delivery ID and sequence;
- keeps the causal parent delivery ID;
- reads the retained local terminal unit;
- has no remote candidate identity;
- skips transport disposition and terminal-source retention.

No synthetic remote locator, candidate-token convention or no-op SMB object is
permitted.

## 5. Cleanup state machine

Retention eligibility remains outcome-age based. No new per-delivery timer or
schema table is required for the minimal implementation.

```text
ELIGIBLE
  -> REMOTE_SOURCE_PURGED
  -> LOCAL_TERMINAL_DELETED_OR_ARCHIVED
  -> WORKSPACE_PURGED
  -> SNAPSHOT_PURGED
  -> DATAFRAME_RECEIPT_PURGED
  -> LEDGER_ROW_CAS_DELETED
```

Each transition is recomputed from durable evidence and idempotent physical
operations rather than trusted in-memory progress. On failure, stop at that
step; the terminal ledger row remains discoverable. A later reconcile repeats
already completed steps safely.

Remote archive is deliberately absent. Under local archive policy, remote purge
still happens first and the protected local unit moves to the configured local
archive before the remaining cleanup sequence.

## 6. Health and observability contract

Source readiness is independent from aggregate daemon liveness:

| Condition | Source intake | Aggregate signal |
|---|---|---|
| probe passed | open | `UP` unless another invariant dominates |
| temporary network/server failure | closed, bounded retry | `DEGRADED` |
| deterministic permission/namespace/object mismatch | closed until successful reprobe | `DOWN` |
| optional CHANGE_NOTIFY unavailable after capability pass | open through polling | `DEGRADED` at most |
| remote terminal purge retryable failure | no loss of ledger authority | `DEGRADED`, escalating by age |
| cleanup contradiction | closed for affected source/cleanup lane | `DOWN` |

New diagnostic/log vocabulary must distinguish capability phase, temporary
unavailability, incompatible namespace, cleanup retry and cleanup contradiction.
Fields stay value-free as defined by ADR-0025. Empty successful probes and
retention reconciles are silent.

## 7. Threat-to-control traceability

| Threat | Risk | Required control | Verification |
|---|---|---|---|
| producer reads/modifies claimed or terminal object | tampering/disclosure | producer denied private namespace; service tokenizes names | two-identity live negative tests |
| service silently widens permissions by creating directories | elevation of privilege | pre-provisioned namespace; no runtime mkdir | adapter regression plus missing-directory live test |
| malicious/corrupt token selects another object | tampering/data loss | closed token value, fixed outcome path, exact regular-file delete | traversal, wrong-kind and sibling-object tests |
| retry deletes processing object | data loss | retention rejects processing state/path | unit, adapter and live contract tests |
| remote terminal copies grow without bound | disclosure/denial of service | outcome-age eligibility and ledger-backed exact purge | retention clock and live round-trip tests |
| crash loses cleanup retry authority | repudiation/orphaned data | ledger row deleted last by CAS | crash injection at every cleanup step |
| replay targets original remote object | tampering/data loss | explicit replay source detachment | replay finalization/retention regression |
| probe leaks operator data | disclosure | reserved empty token, safe fields, best-effort cleanup | logging/health redaction tests |

## 8. Implementation scopes

### H0 — Decision and registry alignment

Deliverables:

- ADR-0025;
- this worknote;
- DATA-IMPORT-01 release/operations alignment;
- `OPS-3`, `OPS-8`, `OPS-9` and security/threat registry updates; the duplicate
  active reuse of historical `OPS-7` is removed.

Gate: documentation link/convention checks. No runtime claim is made.

### H1 — Port split and replay semantics

Deliverables:

- split source access, common immutable snapshot storage and optional
  terminal-source retention behind application-owned ports;
- replace duplicated local/SMB delivery-token hashing with one
  `ImportManagedObjectId` value/factory;
- preserve the exact existing token formula and recovery support for both
  legacy snapshot-reference prefixes; do not rewrite pinned evidence;
- explicit replay source-detached behavior;
- composition updates with no adapter-to-adapter dependency;
- architecture tests that keep SMB names/types out of application contracts;
- focused application tests for ordering, idempotency and replay plus one
  snapshot-store contract corpus used by local and SMB flows.

Stop condition: a replay can reach a terminal outcome without any SMB call.

### H2 — Pre-provisioned namespace and capability gate

Deliverables:

- remove runtime directory creation from managed import;
- add `probe` namespace and exact probe primitives;
- add one transport-neutral source-readiness coordinator/result with an SMB
  probe implementation; do not add an SMB-specific readiness service;
- reuse the existing SMB session pool, share client, exception mapper and
  CHANGE_NOTIFY infrastructure; do not create a parallel client stack;
- source-scoped health state and bounded reprobe;
- packaging/operator runbook for Samba and Windows permission mapping.

Stop condition: missing or incompatible namespace closes intake before a real
candidate is renamed, while unrelated sources continue.

### H3 — Remote terminal retention

Deliverables:

- managed-object/outcome-scoped terminal-source port with exact SMB
  regular-file implementation;
- transport-neutral remote-remnant-first retention ordering with ledger row
  last and no transport switch in the application service;
- idempotent absence and contradiction handling;
- retry/backlog diagnostics and health.

Stop condition: crash injection at every boundary converges without an
untracked remote copy or premature ledger deletion.

### H4 — Published documentation and operational UX

Deliverables:

- capability/module docs reflecting implemented ports;
- managed-import operator guide with namespace and two-identity ACL matrix;
- configuration reference and diagnostics catalog generated from code;
- Samba/Windows examples that clearly separate sync fetch from managed import.

Stop condition: published docs describe the implemented runtime and never
claim that the application audits ACLs.

### H5 — Qualification and release evidence

Deliverables:

- mock-free Samba matrix with producer and service identities;
- server-family skips recorded for unavailable Windows/NAS targets;
- packaged fresh-install/upgrade/rollback exercise;
- full committed-HEAD verification gate.

Stop condition: every supported family passes claim, capability, disposition,
retention, reconnect and negative-permission scenarios.

## 9. Out of scope

- creating shares, directories, users or ACLs from the Java process;
- a general SMB ACL inspector;
- remote archive/cold storage;
- recursive or wildcard deletion;
- changing ordinary sync fetch into a mutating consumer;
- a universal mutable remote-file abstraction or speculative transport SPI;
- moving the import receipt/ledger cleanup saga into generic platform
  maintenance;
- distributed cleanup workers, broker/outbox or cross-host lease;
- filename/path exposure in diagnostics, status or health;
- claiming Windows Server or NAS support without live evidence.

## 10. H0 evidence

H0 documentation evidence on 2026-08-26:

- `make docs`: 792 link occurrences, 280 unique, 708 OK, 0 errors and 84
  intentionally excluded;
- `make test-one MODULE=bootstrap/ioc-app TEST=DocumentationConventionTest`:
  one test, zero failures/errors/skips, reactor build successful;
- `git diff --check`: clean.

At the H0 checkpoint implementation remained open under H1-H5, and the relevant
known-issue entries could not be closed by documentation alone. The pre-scope
verification ceased to be fresh for that documentation tree.

## 11. H1-H4 implementation evidence

Implementation evidence on 2026-08-27:

- application-owned `ImportManagedObjectId`, source-occurrence kind, readiness,
  snapshot and terminal-source retention contracts contain no SMB endpoint,
  path, status or adapter type;
- one `LocalFilesystemImportSnapshotStore` owns bounded transfer verification,
  SHA-256, force, atomic publication, both legacy reference prefixes and purge
  for local, SMB and replay flows; the SMB module has no ingest-adapter
  dependency;
- SMB managed import performs no runtime namespace creation, gates listing and
  claim with the pre-provisioned private-object probe, uses the existing shared
  session pool and rejects directory or reparse-point deletion targets;
- replay reaches finalization and retention without source disposition or
  terminal-source calls; forward retention executes remote source, local
  terminal, workspace, snapshot, receipt and ledger-CAS cleanup in that order;
- focused application/adapter/bootstrap tests passed with zero failures,
  including 28 ArchUnit cases, source-scoped admission, value-free health,
  replay detachment, snapshot compatibility, reparse rejection and cleanup
  failure ordering;
- generated `DIAGNOSTICS-CATALOG.md` is current; `make docs` reported 794 link
  occurrences, 281 unique, 710 OK, zero errors and 84 excluded;
- `DocumentationConventionTest` and `git diff --check` passed.

H5 external qualification is deliberately not reported as passed in this
environment. `SmbManagedImportHardeningContractTest` compiles and is opt-in; it
was skipped because no approved two-identity live fixture was supplied. Docker
is installed but its daemon socket is unavailable to the current user. Windows
Server/NAS targets and packaged fresh-install/upgrade/rollback stands are also
unavailable and remain explicit skips. The final committed-HEAD `make verify`
is still required after the implementation commit.
