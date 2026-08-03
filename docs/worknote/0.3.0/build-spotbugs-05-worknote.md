---
title: "BUILD-SPOTBUGS-05 blocking ratchet worknote"
version: "0.3.0"
work_item: "BUILD-SPOTBUGS-05"
status: "Implementation verification"
document_type: "Execution worknote"
source_of_truth: false
language: "en"
---

# BUILD-SPOTBUGS-05 — blocking exact-finding ratchet

Authority remains with the [R030-BUILD goal](goals/R030-BUILD-build-quality.md),
the [build-quality ledger](evidence/build-quality-ledger.md) and the
[status matrix](status-matrix.md). This worknote records implementation and
verification detail for the report-only to blocking transition.

## Outcome

The existing 19-module production-bytecode scope is unchanged. SpotBugs Maven
Plugin `4.10.3.0` and engine `4.10.3` still run with `effort=Max`,
`threshold=Low` and `includeTests=false`; FindSecBugs remains out of scope.

The 76 remaining reviewed findings from BUILD-SPOTBUGS-04 are represented by a
structured accepted-findings document rather than a hand-maintained operational
filter. Ordinary Maven `verify` analyzes unfiltered bytecode, compares every raw
finding with that document and fails on any new, stale, moved or metadata-drifted
entry. Filtered reports remain the quiet day-to-day view; raw reports remain the
enforcement and triage evidence.

## Decision: exact gate instead of a second `spotbugs:check` pass

The standard Maven [`check` mojo](https://github.com/spotbugs/spotbugs-maven-plugin/blob/spotbugs-maven-plugin-4.10.3.0/src/main/groovy/org/codehaus/mojo/spotbugs/CheckMojo.groovy)
executes the SpotBugs analysis goal before checking its result. Running it after
a separate raw pass would analyze the same bytecode twice and would still leave
exact occurrence accounting to a project-owned control. BUILD-SPOTBUGS-05
therefore adopts a stronger equivalent: one native raw analysis followed by a
JDK-only exact-baseline verifier in the aggregate module.

The filtered projection uses the version-pinned SpotBugs workflow
[`Filter`](https://github.com/spotbugs/spotbugs/blob/4.10.3/spotbugs/src/main/java/edu/umd/cs/findbugs/workflow/Filter.java)
over the existing raw XML. This is a presentation step, not the enforcement
boundary. The coupling must be revalidated whenever the engine is upgraded.

## Tracked and generated artifacts

| Artifact | Role |
|---|---|
| `build-support/spotbugs-report/spotbugs-accepted-findings.xml` | Single tracked source of truth for 74 accepted findings and 68 selectors |
| `target/build-quality/spotbugs-accepted-filter.xml` | Deterministically generated `FindBugsFilter`; never committed or edited |
| `<module>/target/spotbugs/spotbugs-raw.xml` | Unfiltered module evidence consumed by the blocking gate |
| `<module>/target/spotbugs/spotbugs.xml` and `.html` | Filtered module views |
| `build-support/spotbugs-report/target/spotbugs/spotbugs-raw.xml` | Raw 19-module aggregate |
| `build-support/spotbugs-report/target/spotbugs-raw/spotbugs.html` | Human-readable raw aggregate |
| `build-support/spotbugs-report/target/spotbugs/spotbugs.xml` and `.html` | Filtered aggregate views |

## Identity and review contract

Hard identity consists of module, bug type, instance hash and occurrence,
priority/rank/category, primary class, primary method or field and JVM
descriptor, source path and primary bytecode offset. Source line is stored for
diagnostics but is advisory. The two current findings without a bytecode anchor
fall back to their otherwise exact class/member identity.

SpotBugs may assign one hash to several occurrences. The current baseline has
four such hash groups and nine instances, including three occurrences in
`PipelineRunner.executeInRunScope`. Consequently neither a hash set nor a
method-level filter is accepted as the gate: an additional occurrence fails raw
comparison even if the generated presentation filter also matches it.

Every baseline entry carries a stable evidence ID, disposition, owner,
rationale and review condition. Every presentation selector requires bug type,
exact class and an exact method and/or field. Package-, category- and
pattern-wide selectors are rejected. Fixed findings leave stale entries and
therefore fail until the corresponding acceptance is removed.

## Fail-closed checks

The root `validate` phase compiles the dependency-free verifier sources, runs
the existing scope matrix plus the baseline matrix, validates all accepted
entries and materializes the operational filter before child modules build.

The late aggregate gate requires:

- raw and filtered XML for every analyzed module;
- no raw report for excluded modules;
- engine version `4.10.3` and `errors=0`, `missingClasses=0`;
- exact equality between 74 accepted identities and the raw findings;
- zero findings in every filtered module report and aggregate;
- exact multiset equality between the raw aggregate and the 19 module raw
  reports;
- both filtered report pairs and the raw aggregate HTML.

The dedicated fixture harness currently covers three happy paths and seventeen
negative scenarios: a new hash, a third same-hash occurrence, stale acceptance,
bytecode and priority drift, engine drift, analyzer errors/missing classes,
occurrence-max drift, visible filtered output, aggregate omission, selector
mismatch, duplicate IDs and identities, missing rationale, broad suppression
and malformed hash. The
existing shared harness continues to cover four scope/report happy paths and
fifteen negative reactor scenarios.

## Operating procedure

1. Fix a new finding and add a focused regression by default.
2. If acceptance is justified, add exactly one reviewed baseline entry with
   evidence, owner and review condition. Never edit generated filter output.
3. Remove stale acceptance in the same change that removes its finding.
4. Treat compiler, JDK, plugin and engine upgrades as explicit rebaseline
   events. Review every hash, occurrence, bytecode and rank delta; do not provide
   an accept-all refresh command.
5. Give every new reactor module an explicit scope disposition before it can
   build. An analyzed module must produce both report projections.
6. Preserve raw and filtered aggregates as CI artifacts and inspect the staged
   baseline diff independently during review.

## Verification ledger

| Check | Result |
|---|---|
| Root contract validation | 4 shared happy / 15 shared negative; 3 baseline happy / 17 baseline negative |
| Focused module lifecycle | `platform-concurrency` produced 2 raw / 0 filtered findings without a second analysis pass |
| Adoption focused 22-project aggregate run | 77 raw / 0 filtered findings; raw HTML generated; exact verifier passed after dual-primary annotation characterization |
| Recorded follow-up canonical 24-project `make verify` | 24/24 passed in `01:40`; 182 suites / 837 tests / 0 failures / 0 errors / 2 external SMB skips |
| Reports and analyzer health | 19 raw XML with 76 findings; 19 filtered XML/HTML with 0 visible; raw/filtered aggregates; errors/missing classes `0/0` |
| `SB04-016` removal proof | Exact gate first rejected a transient compiler-generated `RCN_*` replacement; after structural cleanup, focused and full runs contain neither `IS2_INCONSISTENT_SYNC` nor `RCN_*` |
| `SB04-029..030` removal proof | Root regression exposed the previously wrapped nullable dereference; explicit source leaf validation removed both findings. Focused source-adapter test and Maven verify/SpotBugs passed with 0 adapter findings; full reactor intentionally not rerun |
| Mutation proof | Removing `SB04-089` only from a target-local baseline copy returned exit `1` and reported its raw finding as new |
