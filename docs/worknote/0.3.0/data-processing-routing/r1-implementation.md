# R1 module and compiler skeleton

Status: implemented on `module/platform/router`, 2026-09-27; not activated in
the application. Source baseline before R0/R1: `f43037ee87ef`.

## Delivered boundary

- `adapter-processing-camel` is one internal Maven adapter. Its neutral
  `contract`, `compile` and `runtime` packages have no IOC, application or Spring
  dependency. The application bridge is deferred until its first real typed
  consumer in the processing slices; R1 does not declare an unused port or wire
  Camel into bootstrap.
- Immutable plan descriptors express named views, registered operations,
  destinations and ordered AND/OR/NOT conditions. Validation rejects invalid or
  duplicate IDs, missing references, view cycles, unknown predicate arguments,
  empty groups and bounded plan/view/branch/condition limits before Camel starts.
- The compiler generates local `direct:` operation and destination routes from
  registered processors. The isolated runtime owns the Camel context and producer,
  restricts requests to compiled endpoints, propagates operation failures without
  redelivery, and closes its resources. These routes are a technical skeleton:
  configured selection, view dependency execution, recovery, no-match and IOC
  diagnostics are R2/R3 work, not current behavior.
- Parent dependency management and all module inventories include the adapter.
  Direct Camel API/model imports have explicit dependencies; `camel-direct` and
  `camel-core-languages` remain explicit runtime dependencies. Removing the
  latter in a focused experiment caused startup to reject the missing `simple`
  language, so the runtime closure is tested rather than assumed.
  ArchUnit guards both inward module boundaries and neutral package separation.
  The build admits the module to test lifecycle, local and aggregate JaCoCo,
  SpotBugs, CPD and PMD scopes and report ordering. Existing quality floors and
  analyzer baselines were retained.

## Final-worktree verification

- Focused `./mvnw -B -ntp -pl adapters/adapter-processing-camel -am verify`:
  8 tests, 0 failures, including the architecture suite.
- `make verify`: 26 reactor projects passed. Test lifecycle: 202 fast suites,
  67 integration suites, 5 external shells, 264 deterministic offline suites.
  JaCoCo: 20 production groups, 19 local reports; 22,339/24,853 lines
  (89.88%) and 7,417/9,212 branches (80.51%). The Camel module has 167 covered
  and 6 missed lines, 74 covered and 0 missed branches. SpotBugs accepted its
  existing 120 reviewed findings with 0 visible; CPD retained 24/24 groups.
- `make pmd-analysis`: passed with 0 blocking findings and 22/22 advisory
  findings. `make pmd-watchlist`: passed; no finding in the new module. Raw
  SpotBugs, CPD and adopted PMD reports contain no new-module findings.
- `make security-scan` against the existing offline NVD cache: 0 unsuppressed
  vulnerabilities in the report. The Maven dependency tree resolves the Camel
  family consistently at 4.22.1. The Dependency-Check report does not list
  every resolved Camel jar, so this offline result is limited to the artifacts
  it actually analyzed; it is not a complete transitive-security attestation.
  The new internal adapter initially received a
  low-confidence CPE match for the unrelated Processing Foundation product;
  one exact-package, exact-CVE, expiring false-positive suppression records that
  disposition. It does not suppress Camel dependencies or other findings.

This is deterministic offline build evidence, not customer acceptance or
external-system qualification. R2 must make selection and dispatch real before
the router can take production traffic; P3/P4 must integrate document and import
entries without duplicating the current preparation path.
