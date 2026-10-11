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

Capture now keeps integer row references for initially empty rowid families;
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
