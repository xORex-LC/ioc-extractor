# 0024 — Managed contract-driven dataframe import

## Status

**Accepted on 2026-08-23. P0 contract and boundary baseline implemented in the
0.3.0 candidate; runtime intake and canonical mutation remain disabled and are
subject to later implementation gates.**

This ADR supersedes ADR-0015 only where that decision could be read as banning
all CSV input. Hand-maintained CSV lookup/seed storage remains retired. The sole
exception is a new managed delivery workflow whose versioned contract is
recognized, validated, staged and committed into SQLite truth. CSV never
becomes a second source of truth.

## Context

Operators must be able to place an exported-shape CSV delivery in a dedicated
local or SMB source and have the service import it with low detection latency.
The file name and column order are not stable identifiers. One delivery may
contain compound rows and may map a logical row to multiple fields of one
artifact. Existing active rows may require enrichment, replacement or explicit
clearing under a source-specific policy. Missing source rows must have no
effect.

The workflow crosses several existing authorities: ingestion ownership,
configuration, artifact identity, lifecycle confirmation, canonical storage,
immutable export slots, mutable projection and remote transport. Reusing an
existing one-phase CSV or per-row write path would permit ambiguous contracts,
partial cross-artifact effects, unbounded memory, or crash windows that cannot
be reconciled.

## Decision

### 1. Treat every file occurrence as one managed delivery

Each stable source object is admitted as a distinct occurrence with a
non-reusable delivery ID and a durable monotonic global sequence. Identical
bytes delivered again are a new occurrence. Retry continues the original
delivery and pins its immutable snapshot, contract ID, explicit version and
behavior fingerprint. An operator replay creates a causally linked new
delivery.

Local and SMB sources have separate ownership adapters but implement one
source-lifecycle port. Atomic claim/rename is the default ownership boundary.
WatchService and SMB change notifications are latency hints; complete listing
and ledger reconciliation remain correctness paths.

### 2. Compile a closed declarative contract catalog at startup

`ioc.dataframe-import` separates source trust from data shape:

- sources declare transport/location, an allowed contract set and one authority
  profile;
- contracts declare strict charset and CSV dialect, exact structural
  recognition, column mappings, ordered registered transforms and policies;
- authority profiles cap artifacts, related routing, formula preservation and
  destructive merge behavior.

The framework-free catalog compiler resolves all references, reports semantic
errors collect-all, and emits deterministic SHA-256 behavior fingerprints. A
catalog is either fully executable or absent; no partially resolved catalog is
published. Unknown `ioc.*` keys continue to fail through the existing strict
configuration preflight. Import is disabled by default.

### 3. Preserve tri-state cells and patch only present logical rows

Mapped cells retain `ABSENT`, explicit `NULL`, or concrete `VALUE` until merge.
`ABSENT` never mutates a field. An authoritative `NULL` may clear an existing
nullable public field. Other declared merge policies may preserve, fill,
replace non-null values, or reject conflicts.

Only active records at the transaction's `asOf` instant participate in
matching. Historical records are ignored. A changed identifying value is a new
observation, not an in-place identity rewrite. Rows absent from a delivery are
not deleted, modified, renewed, or otherwise observed.

Contracts default to `as-is` processing and `target-only` routing. Processed and
related-artifact modes are explicit. Compound fields in one artifact row remain
one logical row; no cross-artifact relationship graph is introduced.

### 4. Separate canonical row identity from active match alternatives

Artifact identity gains named, versionable match-key definitions. A contract
references these names rather than embedding SQL or Java behavior. Match keys
are alternative active-record lookup paths and do not by themselves change the
canonical row-key formula. Any physical identity/alias migration requires
collision preflight and must preserve existing canonical and lifecycle IDs.

Zero matches means insert, exactly one means policy-controlled patch, and
multiple matches is a conflict. V1 does not attempt automatic conflict
resolution. Within-delivery duplicate behavior is declared and deterministic.

### 5. Stage off-heap and promote atomically across artifacts

Parsing and mapping stream into a protected per-delivery staging store. The
sealed stage is immutable input to one dataframe-owned canonical promotion
transaction. That transaction covers every accepted artifact branch plus
lifecycle/provenance, match aliases, export-slot effects, artifact revisions
and an import receipt. A logical row's branches and the delivery's accepted set
are all-or-none at this boundary.

The service database owns delivery coordination; the dataframe database owns
canonical commit authority and its idempotency receipt. Recovery resolves the
cross-database saga by consulting durable evidence. It never attempts a
cross-database transaction.

### 6. Extend lifecycle and export-slot behavior without changing their authority

Accepted observations use the ADR-0020 lifecycle boundary. A configured
unchanged-row policy decides whether an exact no-op renews validity. Public
time-like fields may be imported as ordinary public values but never become
lifecycle authority. Missing rows have no lifecycle effect.

For artifacts with external IDs, a positive imported ID is a requested
ADR-0021 export slot in a declared profile, not canonical identity. An
unoccupied request is preferred; an occupied request falls back to the lowest
available slot. Existing survivors keep their assigned slot unless a declared
mismatch policy rejects the row. Allocation must use sparse free ranges rather
than work proportional to the largest requested value. Artifacts without an
external ID, including `address_blacklist`, reject slot mapping.

Public insert/update/clear mutations advance the affected artifact revision
once per delivery. TTL-only renewal and true no-op do not. ADR-0022
revision-significant immutable delivery and ADR-0023 bounded reconciliation
remain authoritative.

### 7. Keep framework and integration mechanisms at adapter boundaries

The contract, policies, use-case ports and orchestration remain in
`ioc-application`. Spring configuration belongs to `ioc-app`; Commons CSV to
the CSV adapter; SQLite/JDBC to the storage adapter; local file watching to the
ingest adapter; and SMB operations to the SMB transport adapter. No new Maven
module or broker is introduced by this decision.

Control events may nudge detection, drain, projection, lifecycle scheduling or
publish. They carry no row data and are never ordering, idempotency or recovery
authority. The delivery ledger and periodic reconcile remain authoritative.

## Consequences

Positive consequences:

- operators can declare multiple trusted dataframe shapes without relying on
  file names or column order;
- authority ceilings prevent a contract from silently escalating mutation
  rights;
- bounded staging and set-oriented promotion can support large deliveries
  without heap or transaction work per source row;
- active-only patch semantics preserve unrelated canonical data;
- a dataframe receipt closes the commit-before-service-update crash window;
- local and SMB intake share business behavior without sharing transport code.

Costs and limits:

- the runtime requires new service-ledger, staging, alias and receipt schemas in
  later slices;
- atomic cross-artifact promotion increases the size and importance of one
  SQLite writer transaction;
- conflicting active matches are rejected rather than automatically repaired;
- source ownership guarantees depend on filesystem/server capabilities and
  require adapter contract qualification;
- P0 publishes only the disabled configuration and framework-free contracts;
  it does not accept or mutate files.

## Rejected alternatives

- **Use CSV as lookup or seed truth:** restores the dual-authority model removed
  by ADR-0015.
- **Identify contracts by file name:** cannot tolerate operator naming or
  upstream export changes.
- **Bind contracts directly to Commons CSV or Spring types:** leaks an adapter
  family into the application core.
- **Update each row/artifact independently:** permits partial delivery effects
  and creates per-row transaction overhead.
- **Load the complete delivery in heap:** makes memory proportional to operator
  input size.
- **Match historical rows:** violates the active lifecycle boundary and can
  resurrect stale identity.
- **Treat the file as a replacement snapshot:** would delete or renew records
  absent from the delivery, contrary to the patch/upsert contract.
- **Use content digest as delivery identity:** suppresses legitimate identical
  re-delivery and lifecycle confirmation.
- **Coordinate through an in-process event bus or external broker:** neither
  provides the required SQLite commit evidence or ordering authority.

## Verification

Implementation is accepted slice by slice. The required evidence includes:

- strict unknown-key and collect-all semantic configuration failures;
- deterministic catalog fingerprints and complete registry/reference checks;
- exhaustive tri-state merge and authority-ceiling tests;
- ArchUnit and Maven guards keeping Spring, Commons CSV, JDBC and SMB out of
  application core;
- reusable delivery-ledger and atomic-writer TCKs;
- migration, active-match, slot-range, failure-injection and crash-recovery
  integration suites;
- local and real-server SMB ownership contract corpora;
- 100,000 and 1,000,000-row bounded-resource evidence;
- packaged upgrade/rollback and a fresh full-reactor release gate.
