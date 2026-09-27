# R3 typed outcomes and recovery

Status: implemented on `module/platform/router`, 2026-09-27. The neutral adapter
still has no document/import consumer or operator configuration binding.

## Technical contract

- A declared `view.recover` edge references one earlier alternate view and a
  nonempty allowlist of reason codes admitted by the caller-owned registry. The
  compiler rejects cycles, direct/indirect recovery chains, an alternate that
  depends on the failed primary, catch-all reasons and unknown references. A
  recovery node is separate from the primary view; it never changes that view's
  meaning. Ordinary view operations and destination processors still run on
  generated Camel routes. Recovery applies only to typed expected unavailability;
  unexpected exceptions propagate.
- One invocation computes each demanded view at most once. A recovered view
  returns the alternate's actual value, while the original view stays failed.
  Optional absence is a separate outcome. A registered predicate may explicitly
  inspect absence; a required absent view blocks with an `ABSENT_REQUIRED`
  reference. A selected branch declares mapping-only view requirements, which
  are resolved after selection and before dispatch. Failure there never causes
  FIRST to select another branch or invokes the no-match default again. Under
  ALL, independent selected branches still dispatch.
- Destinations return a typed `Prepared`, `Filtered` or expected `Unavailable`
  outcome. The preparation result preserves selection, ordered replies,
  selected-but-blocked branches, and bounded per-invocation causal evidence.
  Failure resolutions identify the producing view, original failure reference,
  and actual recovered/unrecovered branch consumers; recovery attempts retain
  the alternate reference when it fails. These records contain no payloads,
  diagnostic severity or durable-write decision.
- `InvocationViews` owns the per-call cache and demand ledger. `CamelRouteRuntime`
  owns Camel lifecycle and dispatch. Neither stores outcome state across calls.

## Verification and limits

Focused module `verify` passed with 40 tests and zero SpotBugs findings. The
local JaCoCo report has 582 covered / 6 missed lines and 267 covered / 0 missed
branches; the pre-existing 11 missed instructions remain. The coverage ratchet
raises covered minima and leaves missed allowances unchanged. Tests include
strict/recovered siblings, complete recovery, failed or absent alternate,
unallowlisted reason, no demand, mapping-only demands, no reselection,
optional absence, typed filtered/failed destination replies and unexpected
exception propagation.

The application bridge will decide final WARN/ERROR disposition using the
returned consumer evidence and existing failure policy. R4 still owns bootstrap
activation, trace/diagnostic hooks, concurrent caller qualification and bounded
shutdown. P3/P4 own IOC operation bindings, document/import admission, output
validation, canonical checkpoint and replacement of the legacy inner dispatch.
This slice is not customer acceptance of host cleanup.

Final production/test tree: `make verify` passed across the reactor. The
test-lifecycle gate verified 205 fast, 67 integration and 5 external-discoverable
suites (267 deterministic offline suites). Aggregate JaCoCo reported
22,759/25,274 lines (90.05%) and 7,607/9,403 branches (80.90%). SpotBugs had
120 accepted baseline findings and zero visible findings; its raw report has no
finding in this adapter. CPD retained 24/24 groups, none involving this adapter.
`make pmd-analysis` passed with zero blocking and 22/22 adopted advisory findings,
none in this adapter. `make pmd-watchlist` passed as an advisory check with 30
findings, none in this adapter. The focused Maven dependency tree resolves the
Camel artifacts at 4.22.1 without a version conflict. No Maven dependencies
were added or changed in R3; the Camel dependency closure remains the R0/R1 one.
