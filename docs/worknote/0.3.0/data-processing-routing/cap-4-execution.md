# CAP-4 execution and qualification

Date: 2026-10-05. Scope: CAP-4 only; no CAP-5 scheduling change or stand deployment.

## Implementation

[ADR-0036](../../../ADR/0036-sealed-document-preparation-workspace.md) owns the
state/recovery decision. One admitted private SQLite connection/cache serves
all document artifacts. Every routed occurrence is stored; global selection
uses full canonical material, preserves first-key order and replaces complete
LAST_NONEMPTY rows including positions and provenance. Preparation seals before
the failure-policy checkpoint; no canonical IDs are reserved by preparation.

Owned repeatable cursors replace document-wide winner, confirmation and receipt
lists. Receipt inserts flush at 128 rows or 1 MiB estimated bindings. Duplicate
confirmation validation uses disk TEMP storage with a 64 KiB cache and a bounded
page count. The canonical mutation session remains transaction-scoped. Receipt
replay shares the writer connection, including a pool with only one slot.

Successful/dry-run/rejected work is deleted. A sealed promotion failure retains
the source/identity/seal for the stable observation. Per-artifact commit markers
are checked before reading or reserving rows, even while the whole receipt is
STAGING. COMPLETE receipts remain usable after workspace deletion. Pinning
checks source byte limits during copying, forces source/metadata writes and
directory changes, and validates hashes. Expired pins are pruned on admission;
active file leases are excluded. Defaults and both configuration guides expose
all nine physical workspace settings. Physical budgets do not change the
semantic processing fingerprint or invalidate earlier complete receipts.

## Qualification status

Implementation is under final qualification. Application tests and the real
disk workspace suite passed initial runs. The full Spring gate identified a
test isolation defect: four independently initialized canonical stores reused
one workspace root and observation. Golden fixtures now own a separate workspace
per store; production pin identity mismatch remains fail-closed.

The suite universe adds one integration class (74 deterministic integration
classes; 291 deterministic classes total). No coverage floor is lowered.

G4 measurements use `make document-workspace-capacity`: incremental generation,
two artifacts, ten percent duplicate observations, independent full-row winner
oracle, real canonical promotion and complete receipt reread. Live heap samples
use explicit GC and are diagnostics, not primary end-to-end latency evidence.
Each fork's databases and frozen runtime are removed on success, oracle failure
and timeout. The cleanup/error contract is independently tested.

Results, final gate outcomes and reviewed analyzer changes will be recorded
before CAP-4 is marked complete. Whole-text/occurrence buffers, full SMB-cycle
time and final service resource acceptance remain later capacity gates.

## Analyzer review: CAP-4-SQL-TRUST

The first complete analysis reports four new SQL identities and removes the
former `JdbcConfirmationReceiptStore.loadRows` identity. The exact baseline
change retains raw visibility and narrows acceptance to four reviewed methods:

| Identity | Review evidence | Reopen when |
| --- | --- | --- |
| CAP-4-SB-001 | Workspace PRAGMAs concatenate validated integral cache/page limits only; quota failure tests | PRAGMA operands admit strings or tuning grammar changes |
| CAP-4-SB-002 | Workspace INSERT/conflict clauses are adapter-owned constants; row/key/artifact values are bound; independent winner oracle | Reducer SQL ceases to be closed templates |
| CAP-4-SB-003 | Receipt SELECT table/column names are admitted immutable schema identifiers, validated and quoted; receipt ID is bound; single-slot replay | Schema admission, quoting or binding changes |
| CAP-4-SB-004 | Receipt COUNT uses the same schema-owned quoted table; complete header/count checks share the read transaction; removed-empty-receipt test | Receipt count/snapshot contract changes |

Null-return path findings, generic close rethrow and loop string-concatenation
signals encountered during development were fixed in code. CPD remained at
24/24 groups in the first complete analysis. Coverage floors and missed-count
ratchets remain unchanged; source-failure ownership and invalid-budget tests
cover additional meaningful failure paths.
