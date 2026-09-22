---
title: "DATA-AGGREGATE-01 — technical design"
version: "0.3.0"
status: "Proposed; product questions pending"
document_type: "Technical design"
source_of_truth: false
language: "en"
---

# DATA-AGGREGATE-01 — technical design

## 1. Authority, scope and readiness

Design authorization: owner request in this conversation. Baseline:
`feature/dataframe/new-artifact`, `e3f88ccf69ccb5147b98169ff6c2ba9742fb0f7a`,
Java 21, Spring Boot 4.0.8 from the live parent POM. P0–P2 implementation is authorized by the owner; later slices require their
normal work-item planning and acceptance. [Discovery](discovery.md)
contains the accepted behavior; this document supplies a concrete engineering
proposal and identifies product decisions that still constrain activation.

Use existing hexagonal modules and dependency direction. Domain/application
remain free of Spring/JDBC/CSV. Do not follow generic skill examples that place
Spring annotations in this repository's application layer. New SQL belongs to
adapter-store-jdbc; configuration/wiring belongs to bootstrap. No new module,
framework, library extraction or worker count increase is proposed. See the
[dependency assessment](library-options.md) for reuse and rejected alternatives.

The feature is larger than a CSV schema addition: last-source semantics require
stable admission, occurrence-preserving preparation and canonical mutation.
Implement those reusable capabilities with configuration selecting aggregate
behavior. Never dispatch on the literal artifact name `ioc_aggregate` in Java.

## 2. Accepted behavior

- New standalone artifact, initially empty, no historical backfill.
- Public order: `name;ip_address;url_match;host_match;hash`; no public id.
- IPv4, DOMAIN, URL and MD5/SHA1/SHA256; one observed carrier per row.
  URL text belongs in url_match, clean domains in host_match. See the
  [network amendment](network-routing-amendment.md) for routing and future correlation boundaries.
- Composite record identity uses all four non-name fields. Reuse current
  canonical key framing (including NULL handling), not a SQL UNIQUE tuple whose
  nullable columns could admit duplicate rows.
- Within a document select the last occurrence with a nonempty label and keep
  that occurrence intact. Keep an unnamed IOC when every occurrence is unnamed.
- Across deliveries, later durable service registration wins among eligible
  nonempty names. Retry/restart does not promote priority.
- Empty-name policy preserves the stored name; unnamed insert stores NULL.
- Extend marker recognition for all five observed production forms; retain
  legacy long FSTEK markers. Global attribution impact is intentional.
- Managed aggregate import uses the existing delivery workflow with an explicit
  versioned contract. It is not automatic cross-table replication.

## 3. Components and ownership

Names below are proposed implementation names, not existing APIs. Prefer
package-private concrete collaborators; ports are only for I/O/use-case seams.

| Owner | Component | Single responsibility |
|---|---|---|
| application.observation | `RegisteredObservation`, `ObservationOrder`, `OccurrencePosition` | Immutable validated admission identity/order and local ordinal; comparison has no clock reads |
| application.port.out.observation | `ObservationRegistrationStore` | Idempotent durable registration and lookup by occurrence identity |
| application.ingest | `DocumentAdmissionService` | Coordinate reservation, ownership and registered observation before processing |
| application.port.out.ingest | `DocumentAdmissionJournal` | Durable document reservation/claim/recovery checkpoints |
| application.artifact.policy | `ArtifactWritePolicy`, `FieldUpdatePolicy`, `FieldUpdateDecision` | Compiled immutable policies and explicit outcomes |
| application.artifact.policy | `LatestRegisteredValuePolicy` | Pure decision over old/new values and their order; no I/O or logging |
| application.pipeline | `IndicatorOccurrenceIndex` | Retain attribution decisions/positions grouped by existing dedup key |
| application.artifact.policy | `ArtifactOccurrenceSelector` | Select whole candidate occurrences for one configured artifact |
| adapter-csv | `IndicatorTypeGate`, compiled column bindings | Interpret mapping type gates and value bindings once per configuration |
| adapter-store-jdbc | `JdbcObservationRegistrationStore` | Allocate/check admission order in dataframe DB |
| adapter-store-jdbc | `JdbcDocumentAdmissionJournal` | Persist document coordination in service DB |
| adapter-store-jdbc | `JdbcFieldProvenanceStore` | Read/write field order metadata on the caller's canonical connection |
| adapter-store-jdbc | `JdbcOrderedRowMutation` | Apply pure policy result with row/provenance atomicity under write ownership |
| bootstrap | `ArtifactPolicyConfiguration` / compiler | Bind, collect semantic errors, compile policies and wire collaborators |

DDD placement follows existing bounded responsibilities: durable artifact
policies belong to application/artifact, not the pure IOC domain model.
The consistency boundary is one canonical row lifecycle plus its field orders;
import retains its existing cross-artifact promotion transaction. Documents
retain per-artifact commits and run recovery; no new run-wide atomicity claim.

Extend existing orchestration with delegation. Do not grow AppConfig,
IngestionService, JdbcCanonicalMutationEngine or DataframeImportCatalogCompiler
into owners of ordering algorithms, source selection and SQL all together.
Use Strategy for the two required update behaviors and composition for mapping.
No generic workflow engine, expression evaluator or service/impl pair per class.

## 4. Mapping and policy configuration proposal

The earlier IP/hash-only YAML sketch is superseded by the owner's network
clarification. The proposed artifact remains ioc_aggregate with output path
./dataframe/IOC_aggregate_generated.csv and accepts all six supported types.
Columns map source.label, bare IP, URL-shaped value, clean domain and hash in
public order. Type gates alone are insufficient: use the structural column
conditions and configurable carrier cardinality in the
[network amendment](network-routing-amendment.md). Exact new gate syntax is
pending P1; no executable preset is supplied here. The proposed write policy
remains last-nonempty selection on name and latest-received/keep-existing update.

Disabled-by-default and filename are proposed rollout choices, not owner-approved
requirements. No shipment preset is changed in this documentation task.
`duplicate-selection` reduces mapped candidate rows with the same record key;
`selection-column` chooses a whole occurrence, never a mixture of occurrences.
For all-empty groups select the first occurrence deterministically and set name
NULL. Existing artifacts use their legacy selection behavior unchanged.

Compile `when-type` as a singleton set and `when-types` as an immutable EnumSet
or equivalent closed set. Reject both together, empty lists, duplicates, unknown
types, and either gate on deferred ID. Preserve absent-gate meaning. Compare
sets semantically for fingerprints (configuration list order is not significant).
Share gate evaluation between ordinary and processed import paths.

Validate field policies against actual columns: no record-key, match-key, public
ID or internal column can receive latest-name mutation in v1. Unknown strategy,
unknown selection column and impossible mapping fail semantic collect-all startup
validation. New policy defaults must preserve existing configurations. Extending
future behavior means a new narrow policy plus registry/compiler support, not
editing every writer. Unsupported policy configurations must fail explicitly.

Use the existing COMPOSITE identity resolver and define one aggregate match key
with the SAME full tuple as its record key. Do not use independent ip/hash aliases
that would weaken full-tuple equality. Canonical resolver trims text and treats
empty/NULL-literal key material as absent; define these normalized values in the
consumer contract. Hash uppercase is explicit in both output and import mapping.

ip_address represents bare IPv4. Decorated IP routing is covered by Q-06 and
the network amendment; never strip a path/port just to populate ip_address.

## 5. Preserve occurrences without repeated classification

Current attribution has positions in `AttributionOutcome`; current global dedup
retains only first indicators for classification. Preserve the attribution
occurrence index across this boundary instead of disabling global dedup.

1. Build one immutable index referencing attribution decisions (type/value,
   source label, text offset and deterministic tie ordinal).
2. Keep classification once per existing unique type/value key, as today.
3. Join the reusable ClassificationDecision to each occurrence needed by a
   policy-enabled artifact, retaining that occurrence's Indicator/source.
4. Apply artifact accepts/filter, then map candidates; reduce by computed
   artifact record key with ArtifactOccurrenceSelector before ID allocation.
5. Keep existing artifacts on the legacy retained list and counters. Explain
   dedup tracing as capability-specific selection when more than one policy is
   active, rather than claiming an occurrence was globally discarded.

This handles distinct raw values normalizing to the same artifact key. Choose
max text position among nonempty mapped labels; a failed candidate mapping is
still a diagnostic under the existing failure policy, not silently hidden by
selection. No extra classification pass per source or per artifact.
Memory is O(document occurrences + unique classification keys), already bounded
by document materialization characteristics; avoid copying full payloads per
artifact. Stream candidate reduction where possible. Profile before further
optimization; arbitrary thresholds from skill examples are not evidence.

## 6. Durable ordering authority

### 6.1 Placement decision

Recommend storing the small **business order authority in dataframe DB**, beside
canonical field precedence. Service DB continues to own files, claims, retries,
queues and status. This keeps a replaced/reinitialized service DB from resetting
an order counter against surviving canonical ranks and supports oneshot without
requiring service coordination tables merely to choose a field value.

This is a deliberate ownership clarification for a new ADR: registration here
means assigning canonical write precedence, not moving ingestion coordination
into dataframe. No filenames, remote tokens, retry state or scheduling queues
belong in this table. It is a single local authority, not a distributed clock.

Proposed schema (exact migration numbers resolved against implementation HEAD):

```text
observation_order_control(singleton_id PK, namespace_id, next_order)
registered_observation(occurrence_id PK, admission_order UNIQUE,
                       registered_at_ms, origin_kind, terminal_at_ms NULL)
canonical_field_origin(artifact, lifecycle_id, field_name,
                       admission_order, occurrence_position, occurrence_id,
                       PRIMARY KEY(artifact, lifecycle_id, field_name))
```

Separate `registerNew` from `resume`: new registration requires durable new-admission
evidence; resume requires the existing registration and never allocates a missing
order. Retrying an unfinished registerNew step uses its journal reservation token
and returns the same registration. Cleanup must retain such replay evidence until
that step is durably closed; expired/retired admission tokens cannot become new
registrations. A missing registration on recovery fails closed.

An atomic register-or-read transaction allocates one positive signed-64-bit order
per occurrence ID, persists it, and returns the same result on replay. Increment
must check overflow and fail closed. Gaps are harmless. Do not derive priority
from UUID, mtime, wall clock, worker dispatch order or source content hash.
`registered_at_ms` is diagnostic only. Requests must carry/verify dataframe
namespace identity; cross-database restore mismatch fails before write.

No registered observation is permission to commit IOC data before the existing
failure-policy checkpoint. Registration is coordination metadata; dry-run remains
side-effect-free and does not allocate real order.

### 6.2 Document admission

A new small service-DB journal records a stable UUID, source location, candidate
signature, token-only private claim destination, registration reference and
phase. A path alone is not occurrence identity.

```text
RESERVED -> ORDERED -> CLAIMED -> LINKED -> TERMINAL
```

- Persist RESERVED before asynchronous hash/extraction, including a unique
  active reservation for the candidate and a hash-independent claim destination.
- Call idempotent dataframe register; its successful transaction is the ordering
  point. Persist ORDERED reference. Earlier reservation is only intent, not a
  competing order authority.
- Claim into the token destination using the existing filesystem ownership
  abstraction, extended with a pre-hash claim operation; then hash the owned file.
- Link observation ID/order to the existing ingest ledger/run and process only
  the claimed object. IngestionService adopts the claim rather than moving it
  twice. Keep source content key distinct from occurrence identity.
- On recovery, replay journal steps; crash after registration returns the same
  order, crash after rename adopts the known destination. Compare detection and
  claimed evidence; ambiguous replacement cannot inherit an old observation.
  Report/reject ambiguity and require a distinct delivery for the replacement.
- Close the journal only after existing canonical/run/file completion. A new
  same-byte delivery gets a distinct UUID. Terminal disposition records safe
  evidence; raw paths/labels are not general INFO telemetry.

Candidate signatures are supporting evidence, not a proof based on size/mtime.
P0 must specify the producer handoff contract (finished file published by atomic
rename, no writes after publication), available file identity, private snapshot
stability checks and recovery behavior when identity cannot be established.
Same-size/same-mtime replacement and writes through an already-open handle must
be qualification cases. Rename alone does not freeze content. Ambiguity must
fail/quarantine; it must not assign replacement bytes the older registration.

This necessarily intersects pre-claim ING-13 and retry ING-11 seams. Limit changes
to preserving occurrence identity/order and adopting the claim; do not declare
all existing ING debts closed. Existing legacy paths remain when policy disabled.
The default proposal requires JDBC service coordination for daemon latest-received;
file-ledger parity is an open owner question, not an in-memory fallback.

### 6.3 Managed import and oneshot

Keep import sequence_no as its existing durable scheduling order. After durable
claim reservation, register its stable delivery ID with dataframe order authority
before processing, persisting the returned reference through claim/stage/promotion.
Cross-path field precedence uses admission_order, NEVER import sequence_no.
Import claim-order scheduling and business registration order are distinct and
must be named distinctly in diagnostics. Administrative replay is a new occurrence
under existing replay semantics; recovery is not a new occurrence.

Oneshot extract allocates an invocation observation and registers before actual
extraction; one invocation retains its context across internal retries. A new CLI
invocation is a new delivery (even after a crashed invocation); do not promise
automatic CLI resume that does not exist. Reuse the dataframe registry and existing
lifecycle wrapper via a dedicated application admission decorator. Help/validation
and dry-run do not allocate orders or eagerly open unrelated stores.

### 6.4 Two database boundaries

No XA or cross-SQLite transaction is claimed. Use an idempotent sequence of local
transactions: durable ingress identity -> dataframe register-or-read -> durable
reference -> processing -> canonical receipt -> service finalization. Never hold
one database write transaction while acquiring the other's writer lock.
Orphan orders without IOC commits are safe gaps; recovery reconciles them from
journal evidence. A missing or contradictory persisted order is a typed failure,
not a reason to allocate a newer order for a retry.

## 7. Canonical mutation

Under existing JDBC write ownership and one effective asOf:

1. Validate registered observation/namespace and canonical operation idempotency.
2. Match the active full-tuple key using current match/record identity mechanisms.
3. Load public row and provenance for only configured mutable fields.
4. Pure policy chooses per field using `(admission_order, occurrence_position)`.
5. Persist public values and their precedence together; update lifecycle,
   aliases if needed, revisions, projection generation and receipt facts.
6. Commit before publishing control hints. Reconcile remains the backstop.

| Input/state | Field result | Public revision |
|---|---|---|
| No row, nonempty value | Insert with incoming origin | Insert |
| No row, empty value | Insert NULL, no value origin | Insert |
| Newer nonempty, different value | Replace and advance origin | Public change |
| Newer nonempty, same value | Advance origin only | Unchanged |
| Older nonempty | Preserve value/origin | Unchanged |
| Empty incoming | Preserve value/origin | Unchanged |
| Same order/position, same value | Idempotent | Unchanged |
| Same order/position, different nonempty value | Reject contradictory evidence | No commit |

Empty observations do not advance field origin: they cannot suppress a later
completion carrying the newest available nonempty name. TTL confirmation remains
based on accepted canonical transaction time, not order time. Field suppression
is not automatically observation rejection. Same observation retry consults the
receipt/commit marker first and cannot renew again or roll back a newer field.

Accepted provenance scope is the current lifecycle. On expiry, archive provenance
with typed history and remove active provenance atomically; recreated lifecycle
starts anew. This follows current active-only matching but means an old previously
uncommitted delivery can establish a name in a new lifecycle. The owner explicitly accepts this recreation. It applies to a previously
uncommitted observation, not replay of an already committed operation. Source-age
limits, explicit revocation and cross-lifecycle suppression would be separate
future contracts; local TTL does not certify current threat activity.

Ordinary writer currently accepts only INSERTED/TTL_CONFIRMED/RESTARTED and uses
insert counts for revision/event decisions. Extend outcomes with separate public
change and metadata change facts, keeping existing inserted counts meaningful.
Update WriteArtifactsStage, ExtractionResult, LifecycleWriteResult, ingestion
change publication and recovery projections consistently. Reuse existing import
mutation primitives rather than duplicating lifecycle SQL inside the new policy.

## 8. Receipts, fingerprints and import integration

Confirmation receipt reuse is preparation reuse, not historical final-state
reuse. A new identical-byte delivery applies cached prepared candidates with its
NEW admission order. Actual retry uses the original observation/order and commit
receipt. Cache selected occurrence positions and incoming name, not whichever
newer stored name happened to win when the receipt was first built. Otherwise an
unrelated source name can be propagated on later redelivery.

Version receipt payload for occurrence metadata. Old receipts cannot supply
unknown selection evidence: fall back to ordinary ETL, preserving observation
identity. ProcessingPolicyFingerprint currently reflects record shapes; adding
fields can invalidate old hashes even with defaults. Design an explicit v2
semantic fingerprint with canonicalized type sets and policy defaults. Treat
changed receipts as cache misses, never silently reuse under changed policy.
Existing export plans need a reviewed fingerprint transition; one rematerialized
slice after upgrade is a documented possible compatibility effect, not a no-op
promise. Admission order itself is NOT part of content/plan fingerprints.

Processed import currently hardcodes source column `source`. Add a compiled
source-label binding: infer a unique target mapped from source.label only when
unambiguous, otherwise require explicit binding. A literal special case for
`name` is prohibited. Preserve tri-state cells until policies resolve them.

Apply source authority first: latest-received is not permission to modify a field
that a contract/source cannot write. Existing import contracts retain their own
merge rules. For ordered fields use the same pure policy with persisted canonical
origin; reject incompatible clear/authoritative merge settings at catalog compile.
Policy resolution is compiled once, not rediscovered for each row.

Pin the effective processed-import policy fingerprint in the sealed staging
workspace and promotion receipt, including sink mapping, source binding, type
gates, occurrence/field policies and processing inputs. The current explicit
import-contract fingerprint alone does not cover all processing dependencies.
Recovery must use the pinned compatible interpretation or stop with an actionable
policy-drift result; never reinterpret staged data under a changed live mapping.

Aggregate import contract must explicitly specify exact-one recognition, dialect,
header mapping, carrier validations, source binding, target-only routing, authority,
normalization and duplicate policy. Add tests for both processed and as-is paths;
as-is skips processing but is not permission for arbitrary unsafe shape. Existing
import duplicate policies are coalesce/keep-first. The owner accepted a new
configurable last-nonempty policy for aggregate CSV: reduce by full normalized
record key, selecting the last accepted row with a nonempty name and preserving
that row intact. Use logical CSV record order, not physical line number (quoted
cells may span lines). All-empty groups retain a deterministic unnamed row.
Extend contract compilation and private staging reduction; do not substitute
coalesce or change existing contracts. Apply normal validation/failure policy
before selection; a later invalid row must not silently bypass it. Compound rows are outside the current one-observation contract; apply the configured
exactly-one-carrier validation described in the network amendment.

## 9. Lifecycle modes, compatibility and activation

Do not silently require fixed TTL for a new ordinary output artifact: existing
oneshot defaults to lifecycle disabled. Implement ordered mutation in both JDBC
compatibility writer and lifecycle writer through shared policy/provenance logic.
Compatibility provenance uses a separate identity scope `(artifact, row_key,
identity_epoch, field)`; lifecycle provenance uses nonreusable lifecycle identity.
Use distinct tables or explicit constrained scope, not nullable-PK ambiguity.
Enabling fixed lifecycle uses existing expire cutover and retires compatibility
provenance with old rows. Managed import keeps its existing fixed-lifecycle gate.

New artifact tables are configuration-driven, but registration, origin and
journal metadata require real versioned migrations (currently both DBs v9).
Plan dataframe v10 and service v10 if still free at implementation time; include
receipt version and private import-stage format bump where changed. Allocation
numbers are not an accepted ADR number reservation.

Safe proposed activation sequence:

1. Stop new intake; finish or explicitly resolve old nonterminal ingestion/import
   work under the old policy. Do not synthesize original cross-path order from
   old timestamps. Check run ledger, claimed files and import stages, not only
   one queue counter.
2. Stop service; capture consistent service/dataframe DB and configuration backups
   plus coordination with owned files. Avoid live copying WAL files independently.
3. Upgrade and migrate; compile policy/identity/import catalogs; verify empty new
   artifact schema and valid registry namespace; no historical backfill.
4. Admit intake only after recovery barrier and metadata consistency checks.
   Legacy unranked in-flight work with new policy enabled blocks readiness with
   actionable recovery instructions. Legacy policy operation remains available
   on the new binary to drain before enabling ordered policy.
5. Qualify fresh install, upgrade and restart on isolated state. Existing active
   lifecycle must admit a new configured artifact without a fresh TTL cutover.

Old binaries may reject a bumped schema: binary-only rollback is NOT promised.
Supported rollback proposal is coordinated restoration of pre-upgrade state with
intake stopped; it loses post-backup accepted writes unless separately reconciled.
This limitation must be explicitly reviewed before production activation. No
silent downgrade or attempt to strip internal tables to appease an old binary.

## 10. Retention, operations and failure contract

Order high-water/namespace are permanent small control facts. Keep active field
origin with its row; archived origin follows history retention. Keep registration
rows while nonterminal, referenced by active/history origin, canonical receipts
or supported recovery evidence. Terminal unreferenced registrations can be pruned
in bounded indexed batches after the configured recovery retention horizon.
Never retain one registration per IOC: registration is per delivery.

Document admission and import orchestration each own an idempotent terminal
handshake: persist the terminal service disposition, finalize registration in a
separate dataframe transaction, and record completion. Reconcile repairs a crash
between these steps. Rejections/quarantine without canonical writes also need
this handshake; receipt existence is not a prerequisite for terminal disposition.
Cleanup cannot remove evidence while that handshake or a registerNew step remains
recoverable. A replay whose registration has expired is rejected, never re-ranked.

The oneshot decorator finalizes its registration on normal success/failure.
A process crash can leave an unresolved registration with no resumable CLI job.
Retain that compact row and expose unresolved count/age until explicit operator
reconciliation verifies the invocation has stopped and resolves its disposition.
Do not age-delete a possibly live long invocation. Bounded automatic retention
therefore excludes unresolved oneshot registrations; a fully automatic lease-based
CLI recovery protocol is deferred rather than silently invented.

Use safe aggregate metrics (registered/pending/stale-field-suppressed/empty-name-
preserved) without IOC labels or unbounded IDs. Existing diagnostic/tracing and
CLI status mechanisms expose receipt/order references in an authorized detailed
view. Propose a small read-only observation-status use case for lookup by delivery
ID, not a new dashboard or durable universal logging subsystem. Default logs do
not dump raw filenames, IOC values or source labels. Document how an operator
explains why a late retry did not replace a newer name.

No new timer per IOC, no global JVM map of row origins, no cache as authority.
Health rejects missing order dependencies, inconsistent namespace and unresolved
admission recovery. A stale field update is a successful policy suppression, not
an operational error. Registration failure stops that occurrence before business
processing; canonical failure rolls back field and origin together.

## 11. Concurrency library and performance

The existing published `io.github.xorex-lc:ioc-platform-concurrency:0.3.0-rc.1`
is already qualified; do not republish it or move its tag. Reactor sources use
lockstep SNAPSHOT coordinates. Reuse its existing guards/executors where already
owned by ingestion/import; no library API change is needed for this design.

Its equal-key exclusion is process-local, and asynchronous FIFO is admission
order, not business order. Persisted field precedence and SQLite transactions
remain authoritative across restarts/processes. Do not add JDBC, IOC observation
models or retry ledgers to this library. Do not replace it with bespoke locks.

Keep register transactions short; parsing, hashing and filesystem/SMB operations
occur outside canonical transactions. SQLite has one writer per database; extra
workers improve preparation capacity, not simultaneous writer throughput.
Batch-fetch provenance for bounded import chunks to avoid one extra connection
or SELECT per cell. Reuse caller connection/prepared statements. Index occurrence
identity, active reservation, field origin and retention candidates. Measure
allocation latency, canonical lock duration and duplicate-heavy preparation
memory on representative data; do not introduce caches or tune pools by guess.

## 12. Product questions and proposed defaults

| ID | Question | Proposal / readiness |
|---|---|---|
| Q-01 | Export on name-only change | Accepted: public name changes trigger ordinary export/delivery cadence |
| Q-02 | Compound carrier row | Resolved by owner: one IP, URL, domain or hash per row; no combined records for now |
| Q-03 | Ordered-policy daemon file-ledger support | Require JDBC service journal for first implementation; preserve old file-ledger policies; asked owner |
| Q-04 | Profile/filename/default activation | Separate aggregate export profile; disabled until qualified; proposed |
| Q-05 | Repeated keys within CSV | Accepted: last row with nonempty name for the same full key; configurable per contract; existing contracts unchanged |
| Q-06 | Network address routing | Confirmed: full URLs and scheme-less host-plus-path in url_match; bare domains/IPs in their own carriers. Scheme-less host:port without path is also accepted in url_match, without inventing a scheme |
| Q-07 | Lifecycle restart and rollback | Lifecycle-local priority and recreation from delayed previously uncommitted input accepted; coordinated rollback/restore limitation still requires review |

Q-01, Q-02, Q-05 and Q-06 are resolved. Q-03 is unanswered; Q-07 lifecycle restart is accepted, while its separate
rollback/restore limitation still requires review. Design alternatives remain localized: Q-01
controls export gating, Q-02 row shape and Q-03 an additional journal adapter.
No framework-boundary exception is currently necessary. If an implementation
prototype disproves these seams, update design and ask about the concrete
contract impact before silently weakening a requirement.

## 13. Verification and decisions to publish

Use the [verification matrix](verification-matrix.md) and expanded
[implementation plan](implementation-plan.md). Before production implementation,
publish an append-only ADR for durable order authority, field policy and
occurrence-preserving pipeline semantics. It extends relevant lifecycle/import
contracts without rewriting accepted ADRs. Update capability docs, module READMEs,
configuration reference, recovery/upgrade guide and release notes with code.

A design review is not an executable qualification. P0 must prove the riskiest
seams (crash before claim, receipt reuse, update-only export, mode compatibility)
before feature activation. No changes to analyzer ratchets, library tags or
module boundary rules are justified merely by this feature.
