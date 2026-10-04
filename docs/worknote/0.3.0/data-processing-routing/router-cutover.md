# Complete Router cutover

## Accepted scope

On 2026-10-04 the operator accepted a complete cutover: production defaults retain
complete original IOC values through Router; URL-to-host/IP cleanup remains an
explicit plan choice. Every processed import contract must bind a named route.
Missing bindings fail startup. AS_IS imports retain their own product semantics.

## Implementation and evidence

- Document dispatch requires a named plan and an owned Router runtime.
- Processed import selects an exact pinned-contract binding; no fallback remains.
- The shipped document plan retains source-observation accounting. KEEP_FIRST
  observes the source deduplication switch; LAST_NONEMPTY retains all occurrences.
  Derived plans may select winners by final artifact key. Both use Router.
- Obsolete dispatch/preparation implementations are removed; their meaningful
  regression tests now exercise the single execution path.
- Production/classpath configuration, operator guidance and architecture describe
  the required model.
- Regression coverage includes semantic/configuration/recovery tests, real YAML
  admission, golden public CSV bytes, ID/provenance and receipt-only recovery.

## Configuration binding correction

The previously documented singular `not` condition did not bind from real YAML:
Spring Binder stops direct recursion of a constructor-bound type. The typed
condition model now binds only its singular child through a resolved map and a
fresh typed Binder scope. The external syntax and strict property shape remain
unchanged. Tests cover nested NOT/groups, typed enum arguments, rejection of
unknown child properties, and the uncommented production-template example.

## Analyzer disposition

The adopted PMD report has no new rule findings. `ExcessiveParameterList` drops
from 11 to 7 because the cutover removes two obsolete `IocExtractionService`
constructors and two `IocExtractionServiceFactory` overloads. The seven remaining
signatures were inspected; the exact ratchet is tightened to this source universe.
The SpotBugs proposal reports exactly two new and two stale identities, both
in `AppConfig`: removing the optional plan lambda shifts
`lambda$extractIocsUseCase$5` to `$4` and
`lambda$prepareLifecycleAdmissionUseCase$8` to `$7`. Their failure-handling bodies,
member signatures and existing policy-noise dispositions are unchanged. The two
existing entries retain their owners and rationale, with refreshed exact method,
hash and source anchors. No suppression is added or broadened, and no rule
exclusion or coverage floor is changed.

The full CPD report retains 24 reviewed groups with no new cutover member.
Coverage passed at 90.99% line and 82.78% branch after removing an unreachable
CSV diagnostic fallback: the caught final `RowMappingException` always supplies
a located, non-empty message. New regression checks cover structured condition
lists, routed filtering, field-view rejection by incompatible mappers, and
one-shot provenance without a delivery key.


## Qualification boundary

Existing O7/O8 evidence describes the historical opt-in implementation and does
not qualify this cutover. No new performance acceptance or external SMB/stand
qualification is claimed. The release checklist for this worktree is `make docs`, `make verify`,
`make pmd-analysis`, `make pmd-watchlist`, and `make lint-shell`. The local
verification context records the exact HEAD and worktree fingerprint; this
cutover does not change the accepted 220 fast, 72 integration and 5 external
suite universe (287 deterministic offline suites).
