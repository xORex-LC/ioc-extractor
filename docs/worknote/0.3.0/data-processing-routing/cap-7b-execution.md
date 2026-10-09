# CAP-7B routing execution qualification

Status: in progress. This is the conditional execution decision from the
[capacity plan](data-processing-capacity-plan.md), not G6 acceptance.
Entry production HEAD: `58359ddd6db12a8d416e16fbd61cec435500aafc`.

## Promotion contract

Retain configuration, demanded views, FIRST/ALL/EXCLUSIVE, short-circuit,
fallback diagnostics, ordered replies, trace, branch isolation and shutdown.
Native consumer UnitOfWork completion and stopped-route behavior are supported
contracts, already checked by the runtime conformance suites. Promotion needs
equal complete-service output and demonstrated net service benefit with simpler
ownership. There is no second selectable engine, runtime fallback, batch-wide
transaction or weaker capacity budget.

The existing document preparation and managed import use real disk cursors;
the experiment must not replace them with materialized lists or deduplicate
occurrences before validation/provenance. Canonical transactions, receipts,
recovery, admission and projection retain their established owners.

## Isolated controls

- [Direct processor control](qualification/capacity/cap-7b-direct-prototype.patch)
  calls the same compiled local processors without template/recipient dispatch.
  It fails existing native UnitOfWork and stopped-route conformance tests.
  It is rejected before performance promotion; IOC CSV equivalence cannot
  establish a legal replacement. No speedup is claimed for this control.
- [Native sequential control](qualification/capacity/cap-7b-native-sequential-prototype.patch)
  sends selected branches through the existing bound ProducerTemplate instead
  of the extra multi-recipient dispatch exchange. All 55 existing compiler,
  routing, recovery, lifecycle and aggregation conformance tests pass. It keeps
  the native view and branch consumers; demand and trace logic are unchanged.
  Compiler/dispatch cleanup is deliberately excluded from this timing control.

Both patches are evidence artifacts, never applied to the production worktree.
`processing-optimization-comparison.py --prototype-patch` compiles them in a
disposable copy of a frozen reference. Setup/matrix errors retain failure
evidence; temporary classes and runtime copies are removed. Inputs, probes,
external libraries, schemas, durability and policy remain equal between pairs.

## Measurement corrections and incomplete evidence

The first diagnostic setup failed because the observer completed a canonical
ownership timer on the separate validation transaction introduced by CAP-4.
Commit `a10cbec4` binds that timer only to methods acquiring active write
ownership. Its regression checks a real commit callback without ownership;
the corrected document/import diagnostic smoke passes. Failed samples are not
valid cost measurements.

One unique-input import fork failed the service ledger's
`updated_at_ms >= created_at_ms` CHECK. Log timestamps move backwards by about
two seconds across admission/staging/retry. The series remains incomplete;
successful earlier pairs do not turn it into a passing matrix. ING-16 retains
the coordination-clock risk separately from Router optimization.

Full-service comparisons, the final decision, compact raw samples and final
repository gates will be recorded below after qualification.
