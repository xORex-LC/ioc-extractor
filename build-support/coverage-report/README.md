# JaCoCo aggregate and coverage gates

## Purpose and boundary

Report-only Maven POM `ioc-coverage-report`: aggregate downstream test execution
for the declared production universe and enforce coverage/test-report integrity.
Its production dependencies establish report membership and reactor ordering;
there is no runtime JAR or published library. Production and test-support projects
must not depend on this POM or its verifier sources.

## Structure and sources of truth

| File | Responsibility |
|---|---|
| [pom.xml](pom.xml) | Aggregate dependencies/generation, stale-output cleanup and late test/coverage checks |
| [coverage-scope.tsv](coverage-scope.tsv) | Complete reactor disposition plus required-local versus downstream-only report expectation |
| [coverage-ratchets.tsv](coverage-ratchets.tsv) | Exact aggregate/module baseline counters, absolute-missed policy and rationale |
| [coverage-floors.tsv](coverage-floors.tsv) | Explicit fixed-floor or ratchet-only disposition for every production group and aggregate |
| [CoverageVerifier.java](CoverageVerifier.java) | JDK-only topology, report/counter integrity and exact floor/ratchet checks |
| [CoverageVerifierTest.java](CoverageVerifierTest.java) | Standalone synthetic reactor/report contract matrix |

Generated outputs are `target/site/jacoco-aggregate/index.html` and `jacoco.xml`.
Module-local reports remain under each owner's `target/site/jacoco/`; execution
data remains `target/jacoco.exec`. Never hand-edit those outputs or commit them
as a replacement for the reviewed policy inputs.

## Universe, lifecycle and comparison

The current scope declares all 31 Maven projects, with 25 covered production
groups. The TCK, root parent and report POMs are outside production denominators.
There are no manual class/package exclusions. Twenty-four production modules
require local XML/HTML plus execution data. `platform-errors` is explicitly
aggregate-only because its bytecode is exercised downstream; absent local data
there is intentional, not a missing-coverage exemption.

Root `validate` compiles/runs the synthetic harness and reconciles the reactor,
report dependencies, scope, local/aggregate topology, ratchets and floors.
Root/child initialization removes stale test/coverage outputs for normal runs.
The late `verify` phase generates the aggregate, invokes
[TestLifecycleVerifier](../test-quality/README.md) and verifies current report
pairs, exact production groups, expected counters and aggregate/module policy.
Missing or unexpected output fails. Analyzer-only `-DskipTests` preservation is
not fresh test or coverage evidence.

The aggregate includes downstream execution; a module's local HTML may show less
coverage than its aggregate group. The complete local aggregate is authoritative.
Codecov receives this verified XML as advisory reporting, not an alternative gate.

| Constraint | Enforcement |
|---|---|
| Ratios | Covered/total line and branch fractions cannot regress from the named baseline; comparisons use integer cross-products, not rounded HTML |
| Branch misses | Absolute missed branches cannot increase even if denominator growth improves the ratio |
| Instruction misses | Cannot increase where `absolute-instructions=enforced`; `observed` retains review context |
| Counter universe | Appearance after a zero-denominator baseline or disappearance of a previously nonzero counter requires review |
| Fixed floors | Aggregate 75% line / 80% branch; domain and application 85% / 90%; other groups explicitly ratchet-only |

Both floors and ratchets apply where configured; `none` in the floor file does
not remove the ratchet. Useful tests should exercise contract outcomes and
failure/recovery boundaries. Assertions or exclusions added solely to raise a
percentage defeat the policy; [TESTING.md](../../docs/TESTING.md) owns the detailed approach.

## Verification and maintenance

Run from the repository root:

```bash
./mvnw -B -ntp -N validate
make verify
```

Root-only validation proves configuration/source contracts, not a fresh aggregate.
The full reactor is needed for downstream execution and the complete report union;
a report-only or skipped-test invocation cannot establish complete release evidence.
Use the Make/CI facade and `make context` for the workspace freshness receipt.

A module/test/coverage change must reconcile all affected scope, dependency,
report and policy inputs together. Inspect local reports to find missing behavior,
then validate aggregate results. Ratchet improvements/universe changes require an
explained reviewed update; never lower floors or refresh baselines automatically.
See [build-quality internals](../../docs/dev/build-quality.md#coverage) and
[Codecov script](../../tools/ci/codecov.sh) for report handoff.
