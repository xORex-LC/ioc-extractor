# CAP-2 execution: generation-owned mutable projections

Scope: CAP-2 only from [the capacity plan](data-processing-capacity-plan.md).
Status (2026-10-05): CAP-2.1–CAP-2.6 implemented and G2 passed.
Baseline: `36873beab79a8fbd856725a955300daea55b830f`, clean tree, fresh passed
verify and PMD evidence. CAP-0/1 resource results remain unchanged.

## Contract and implementation notes

- C8 is a reachable stale-install race, separate from the original storage
  multiplier. A deterministic temporary SQLite/CSV test blocks A after its old
  snapshot, installs and acknowledges B, then releases A.
- One application owner per configured artifact serializes snapshot read,
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

Implementation commit: `f7783dce` (shared owner, snapshot coverage,
acknowledgement contract, baseline race regression and
[ADR 0035](../../../ADR/0035-generation-owned-mutable-projections.md)).
Qualification commit: `61eb89b4` (fault recovery, concurrent writer families,
expiry-only coverage and production bean ownership).

Focused qualification passed:

- Corrected stale-install regression in `DataframeRecoveryIntegrationIT`.
- Core application tests, including coalesced results, advisory correlation,
  untracked generation zero, acknowledgement failure and cancelled waiters.
- Nine real SQLite/CSV cases in `MutableProjectionOwnershipIT` (2.571 s):
  failure before rename, atomic rename failure, after-rename interruption,
  failing acknowledgement transaction, committed acknowledgement with lost
  reply, cancelled CSV construction, expiry-only empty output, one-connection
  safe-clock operation, and simultaneous ingest/import/expiry during a held
  projection cursor. Each owned worker terminates within an asserted bound.

The concurrent case commits all three writer families while the old projection
read is held open. That installed g1 acknowledges only g1, required g4 remains
pending, and the next convergence installs g4 excluding the expired lifecycle.
Recovery cases require no later canonical mutation.

## G2 qualification

| Planned task | Result | Evidence |
|---|---|---|
| CAP-2.1: reproduce stale installation | PASS | The original path fails the timed real SQLite/CSV interleaving; the shared owner passes `DataframeRecoveryIntegrationIT` |
| CAP-2.2–2.3: shared owner and installation fencing | PASS | `GoldenPipelineIT` checks the production bean graph; ingest, oneshot, recovery and convergence share the owner, and the raw installer is not a bean |
| CAP-2.4: reuse, pending work and empty output | PASS | Core owner tests cover acknowledged reuse and generation zero; real SQLite tests cover partial g1/g4 progress and expiry-only header-only CSV |
| CAP-2.5: completion and diagnostics | PASS | Snapshot generation must cover the caller's initial requirement; reused warnings retain counts, timestamp, cause and caller correlation |
| CAP-2.6: failures, restart and concurrency | PASS | Nine `MutableProjectionOwnershipIT` cases cover the fault boundaries above; repair needs no new canonical mutation and cancelled workers/waiters terminate within asserted bounds |
| Immutable output separation | PASS | Existing on-demand export, reusable-slot, manifest, export-ledger and recovery suites pass in the complete reactor |

These are deterministic offline tests with controlled clocks and private
temporary SQLite stores/files. They cover the supported single-process owner;
they do not claim that C8 caused the historical stand publication delay.

## Repository gates and analyzer review

All applicable gates passed on the implemented candidate with the reviewed
SpotBugs identities. Final clean-HEAD freshness is recorded by `make context`
after the closeout commit and the final `make verify` / `make pmd-analysis` runs.

| Check | Result |
|---|---|
| `make verify` | PASS; 27 reactor projects; test lifecycle: 222 fast, 73 integration, 5 property-gated external suites, 290 deterministic offline suites |
| Coverage integrity/ratchets | PASS; 21 production modules and 20 local reports; 91.05% lines, 82.88% branches; no floor lowered |
| SpotBugs | PASS; 126 exact reviewed findings accepted, 0 visible |
| CPD | PASS; 24/24 groups; the touched existing catalog-validation duplication was reviewed |
| `make pmd-analysis` | PASS; 0 blocking, 20/20 advisory findings; changed `AppConfig` retains its existing parameter-list advisories |
| `make pmd-watchlist` | PASS; 44 advisory findings, none in changed production classes |
| `make docs` and explicit changed-document links | PASS; offline links and `git diff --check` |

SpotBugs reported one new exception-policy signal in the shared owner and three
changed existing identities/anchors. Manual review preserved the original
failure, journal-failure suppression, lock release, constant/bound SQL and
temporary-file cleanup contracts. Only those exact findings were accepted;
there is no broad exclusion or analyzer-scope reduction. The test-suite inventory
changed intentionally to include the new integration suite; thresholds remain
unchanged.

Local ignored logs: `.dev/cap-2-verify-reviewed.log`, `.dev/cap-2-pmd.log`,
`.dev/cap-2-watchlist.log`, `.dev/cap-2-docs.log`, and final-HEAD logs
`.dev/cap-2-final-verify.log` / `.dev/cap-2-final-pmd.log`. Durable regression
fixtures and assertions are committed with the implementation and tests.

## Boundaries and cleanup

Test data uses JUnit temporary directories and is removed; no retained capacity
databases are needed for this correctness change. No new executor or row queue,
schema migration or configuration switch was added. Ownership and reuse state
have fixed artifact cardinality and do not retain projected rows.

Mutable output directories remain exclusive to one service process. External
file corruption is not detected by the generation cache and requires explicit
reprojection, as described in ADR 0035. The installed stand service/executable
were not changed; live SMB/provisioned external tests were not run for CAP-2.
No CAP-2 speed or memory acceptance result is claimed. CAP-3 onward and the
whole-service RSS/writer-hold budgets remain open.
