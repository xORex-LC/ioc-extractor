# R2 ordered selection and execution

Status: implemented on `module/platform/router`, 2026-09-27. This is a neutral
technical adapter; document and managed-import traffic still use the existing
application preparation flow until the IOC integration slices.

## Delivered boundary

- Plan admission checks routing mode, branch/default references and registered
  predicates before starting Camel. A registered predicate binds its accepted
  argument names and implementation at compilation. The immutable selector
  evaluates the compiled condition graph once per invocation; Camel does not
  repeat predicate evaluation.
- FIRST selects the first match and stops on an earlier unavailable prerequisite.
  EXCLUSIVE dispatches only after all other reached branches conclusively fail;
  a second match is ambiguous and a reached blockage prevents dispatch. ALL
  evaluates every branch before dispatch, preserving independent matches and
  blocked-branch evidence. NOT propagates blockage, and AND/OR groups stop in
  declaration order. SKIP, REJECT and ROUTE are distinct no-match outcomes;
  the default route is used only after conclusive no-match.
- The compiler generates separate local `direct:` routes for view operations,
  branches and selected-recipient dispatch. The runtime resolves reached
  prerequisite views on demand with an invocation-local cache, then Camel's
  sequential Recipient List executes selected branch processors in order and
  aggregates their replies. A failed selected destination aborts the call; it
  does not reselect another branch. Callers name admitted plans, not endpoints.
- Expected unavailable prerequisites carry stable failure references. An
  unexpected processor/predicate exception aborts before later routing or
  dispatch. The router does not assign IOC diagnostic severity or write data.

## Contract coverage and next boundary

Deterministic tests cover the decision matrix, ordered short-circuit counts,
NOT/blocked propagation, two-match ambiguity, default exclusion on blockage,
single view computation per invocation, dependent-view blockage, destination
failure and malformed Camel aggregation. The module's measured JaCoCo baseline
is 402 covered / 6 missed lines, 175 covered / 0 missed branches and 11 missed
instructions; its ratchet raises only covered minima from R1 and preserves all
miss allowances.

R3 owns explicit recovery edges, allowlisted reasons, original-failure evidence
and demanded-consumer resolution, including mapping-only view dependencies.
R4 owns application activation, concurrent caller and shutdown qualification,
observation/diagnostic bridges. P3/P4 own IOC document/import integration and
removal of the replaced inner dispatch path. This R2 adapter is not yet a
customer-complete host-cleaning feature.

## Verification

Focused `./mvnw -B -ntp -pl adapters/adapter-processing-camel -am verify` passed:
24 tests, no failures; module SpotBugs found zero warnings.

`make verify` passed all 26 reactor projects. The report union contains 204 fast
and 67 integration suites plus five property-gated external shells: 266
deterministic offline suites. Aggregate JaCoCo is 22,573/25,088 lines (89.98%)
and 7,517/9,313 branches (80.72%). SpotBugs accepted 120 existing reviewed
findings with zero visible; CPD retained 24/24 groups. The raw SpotBugs and CPD
reports contain no finding in the changed module.

`make pmd-analysis` passed with zero blocking and 22/22 advisory findings.
`make pmd-watchlist` passed with 30 advisory findings; neither PMD report has a
finding in the changed module. This is deterministic offline evidence, not
external-source qualification or customer acceptance.
