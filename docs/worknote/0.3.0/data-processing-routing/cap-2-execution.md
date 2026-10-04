# CAP-2 execution: generation-owned mutable projections

Scope: CAP-2 only from [the capacity plan](data-processing-capacity-plan.md).
Baseline: `36873beab79a8fbd856725a955300daea55b830f`, clean tree, fresh passed
verify and PMD evidence. CAP-0/1 resource results remain unchanged.

## Contract and implementation notes

- C8 is a reachable stale-install race, separate from the original storage
  multiplier. A deterministic temporary SQLite/CSV test blocks A after its old
  snapshot, installs and acknowledges B, then releases A.
- One application owner per configured artifact will serialize snapshot read,
  temporary CSV construction, atomic installation and durable acknowledgement.
  Building holds no canonical writer admission. Canonical mutations may commit
  during the build; their newer generation remains pending.
- Rows and their covered generation must come from the same SQLite read
  transaction. A pre-build work-state load is not snapshot evidence.
- Acknowledgement advances only installed coverage, monotonically and never
  beyond required work. Covering generation g1 while g2 commits must leave g2
  pending, without rejecting useful g1 progress.
- Queued calls may reuse a successfully acknowledged outcome covering current
  work. Advisory counts and caller correlation must survive reuse. Generation
  zero (lifecycle disabled) is untracked and always rebuilds under the owner.
- No new executor or row queue is introduced. Waiters acquire interruptibly;
  row streaming checks cancellation. A failed install/ack leaves durable work
  available to the existing startup and periodic reconciliation paths.

## Evidence log

The baseline race test failed deterministically in 0.673 seconds: the installed
CSV contained only `old.example`, while generation 2 had already been
acknowledged. Log: `.dev/cap-2-race-before.log` (local, ignored).

The implementation now captures generation inside the streaming transaction,
keeps the raw CSV adapter private to the bootstrap-created owner and moves
acknowledgement from the convergence loop to that owner. The existing schema is
sufficient; acknowledgement accepts partial covered progress while keeping newer
work pending. A separate connection lease for safe-clock sampling also avoids a
nested pool lease in a one-connection configuration.

Implementation and qualification are in progress. Test data uses JUnit temporary
directories and is removed; no retained capacity databases are needed for this
correctness change. Installed stand service/executable are outside this change.
