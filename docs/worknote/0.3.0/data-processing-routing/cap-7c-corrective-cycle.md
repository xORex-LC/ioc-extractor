# CAP-7C corrective cycle after C5

Status: implementation in progress. Entry HEAD is `8f6152d6`; the original
[C5 evidence](cap-7c-c5-qualification.md) remains an unsuccessful qualification.
SQLite, atomic publication, publication UTC, input limits and resource budgets
remain unchanged. The isolated candidate remains outside default composition.

## Ordered work and regressions

1. Bound canonical hydration and transfer by bytes. Reserve actual bounded
   working sets and caches, including both row images, and keep document intake
   from consuming the canonical headroom needed to finish its own work. Prove
   default-policy admission and bounded release with the original row limits.
2. Measure private table/index amplification before changing capture. Remove
   redundant representations of absent before-images, retaining insert/update/
   delete and changed-primary-key semantics. Preserve receipt and field origins.
3. Retain dependency validation while preventing operations with no effects from
   advancing business epochs. Test real cleanup interleaved with preparation;
   changes to origins, slots, deadlines and receipts must still invalidate plans.
4. Make operational document/import timestamps nondecreasing relative to their
   durable record. Test backward UTC and receipt-only finalization. Absolute UTC
   deadlines and the canonical safe-clock rejection policy remain unchanged.
5. Repeat default admission, mandatory 100k C5 pairs and cleanup stress with
   identical data/results, declared configuration and resource ceilings. Retain
   failed samples, stop further pairs after a failed pair, and distinguish
   incomplete qualification from acceptance. Run final verify/PMD/docs gates.

Physical writer improvement alone does not justify activation. Profile remaining
preparation/read/transfer cost before choosing any further reducer change.
Million-row and populated-state expansion depends on the mandatory C5 screens.

## Admission correction

`JdbcCanonicalWorkspaceLimits` owns conservative reservation of byte-bounded
chunks, both row images and six distinct main/TEMP caches. Canonical caches are
capped at 512 KiB; document caches and accepted row/field sizes remain literal.
The standard 64 MiB budget now admits one document plus promotion and control
workspaces simultaneously. Document intake cannot consume that headroom.
`JdbcDocumentPreparationWorkspaceIT` passed all 61 cases with the correction.
An earlier invocation recorded a timeout in a reducer parameterized case while
a large structural SQLite diagnostic ran concurrently. Its failed log is kept;
the final focused run used no competing diagnostic. No timeout or test contract
was relaxed.

## Typed capture and dependency corrections

A structural SQLite 3.45.1 diagnostic created 1.8 million field-position rows
(100k rows × 18 fields), with synthetic fixed receipt/artifact keys. Native,
delta and absent-before copies occupied 1,188,724,736 bytes; dropping absent
before-images alone left 792,489,984 bytes. This is a storage-shape diagnostic,
not pinned-driver, durability or full-service timing evidence.

Capture now streams final native rows for initially empty rowid families;
nonempty families retain sparse real before-images and final typed values.
Key movement, reinsertion, transient deletion, cascades and negative native IDs
retain their observable outcomes. Earlier artifact receipt payloads/positions
stay canonical; pinned actual counts and markers preserve completeness checks.
Field-position statements use bounded batches. Empty history/receipt/version
cleanup validates dependencies without advancing business epochs. Real changes
and deadline crossings still require the existing bounded rebuild.

## Coordination correction

Operational document/import progress uses max(supplied UTC, durable updated UTC).
Terminal import retention keeps its full duration, and audit uses the same
coordination timestamp. Retry deadlines and lifecycle safe UTC are unchanged.
Regression tests cover backward UTC, reopened import state and document
registration finalization after an interrupted terminal transition. Existing
receipt-first recovery/crash contracts remain mandatory. ING-16 retains the
unexplained host-clock cause and independent safe-clock boundary.

## Boundary tests and analyzer review

The final adapter integration cohort passed 505 cases without failures (one
offline external skip). New regressions cover 130 positions per artifact across
an incomplete/complete multi-artifact receipt, bounded batches with long opaque
receipt identities, and receipt-only import finalization after stage deletion
and backward UTC. Recovery advances the service ledger without changing canonical
facts; new canonical work still rejects unsafe UTC.

Capture cursor tests include a business column named `rowid`, typed BLOB values,
`WITHOUT ROWID` fallback, and 257 signed native IDs starting at `Long.MIN_VALUE`.
The physical `_rowid_` cursor has an explicit unstarted state, avoiding both
business-column shadowing and loss of negative addresses. Admission configuration
and intake share a monitor; reconfiguration after intake is rejected even after
the first workspace closes.

`CAP-7C-COR-SQL` reviews three new exact SpotBugs SQL identities: the quoted
catalog existence probe, fixed empty-family marker DDL, and a pinned actual-count
query with a bound receipt identity. Three prior SQL identities were refreshed
for their changed bytecode/method anchors. Their trust boundary remains validated
adapter identifiers and literal predicates; business data remains bound. The
genuine admission synchronization findings were fixed rather than accepted.
No analyzer scope, coverage floor, missed-branch budget or input quota was relaxed.
The second full gate retained a two-branch missed-count regression and a new
cursor naming signal. The cursor now uses `nativeAddress`; redundant null
validation of its adapter-owned paging token was removed. A wide-row hydration
regression checks exact Unicode/BLOB payloads, all keys and rollback across
multiple byte-triggered flushes. Its focused suite passes; the earlier failed
gate is not reported as successful verification.
Earlier failed gate logs remain under the local corrective evidence directory;
the final full gates and repeated C5 results are recorded below when complete.

The third full gate failed two existing reducer invocations (cache 64/4096 KiB,
seed `4204`) at their unchanged 20s timeout. The interruption reached seal-file
I/O; host I/O PSI at diagnosis was 14.40% full over 60s. This identifies resource
contention as a possible contributor, not a proven sole cause. Owner: CAP-7C;
disposition: preserve `.dev/corrective-verify-3.log`, execute the complete gate
under declared two-worker Maven parallelism (`MAVEN_OPTS=-XX:ActiveProcessorCount=2`),
and retain this qualification limitation. No automatic retry, test-universe change,
timeout increase or workload reduction is introduced.

That bounded full build passed all gates: JDBC 506 cases (one external skip),
aggregate line/branch coverage 92.32%/85.76%, SpotBugs 168 exact accepted identities
and zero visible findings, CPD 24/24 groups. Subsequent PMD identified two new
complexity findings. Query-shape construction and one-row byte validation were
extracted from execution without adding payload encodings or changing flush order.
The focused capture/hydration suite passes. The query helper changed the existing
read SQL finding's observed priority/rank to 3/15; its exact selector was reviewed
again against the same quoted-catalog/bound-value boundary. Final gates follow
this last source refinement.

The final source gate passes with two Maven workers: aggregate coverage
92.32%/85.78%, SpotBugs 168 exact accepted/zero visible and CPD 24/24.
PMD policy passes at its unchanged 20 findings. The changed-source policy signal
is the existing 13-field `DocumentAdmission` constructor; its shape is unchanged.
Watchlist execution passes and retains 80 report-only signals. Workspace-factory
lease signals were reviewed against callback ownership on success and explicit
failure closure/permit release; fatal-error rethrows preserve the original error,
and wrapped exceptions preserve their cause. No suppression/count budget was added
for these watchlist signals. Offline documentation links pass. C5 measurement
starts only after these source changes are committed and Maven has stopped.
