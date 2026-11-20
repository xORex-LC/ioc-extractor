# Testing

[Русская версия](ru/TESTING.md)

Tests establish supported behavior and protect it against regression. Coverage
identifies code that needs investigation; a percentage does not establish that
the right result, invariant or recovery outcome was checked. A passing quality
gate and a review of test quality are both required.

This document owns repository-wide test and coverage policy. The
[build-quality capability](dev/build-quality.md) explains its Maven/report
implementation; [CONVENTIONS](CONVENTIONS.md) owns documentation and code style.

## Test design and failure policy

### Choose cases from the contract

Before adding tests, identify the observable guarantee, its risk and the outcomes
that would distinguish a correct implementation from a plausible defect. Use the
public API, port contract, documented format or invariant as the oracle. Derive
expected values independently of the algorithm under test.

| Concern | Useful evidence |
|---|---|
| Normal operation | Exact result, ordering or durable state that the caller relies on |
| Boundary | Immediately before, at and after a meaningful threshold; empty/absent/distinct values where supported |
| Rejection | Exception/result category and the protected state or effect that must remain unchanged |
| Partial failure | Which effects occurred, which did not, and the reported primary/secondary failure |
| Recovery/idempotency | Retry/restart reaches the supported state without repeating a committed effect |
| Ownership/concurrency | Copy or alias behavior, ordering/exclusion scope, bounded cancellation and released resources |

Select relevant cases; this is not a requirement for six tests per method.
A fixed defect receives a regression that fails for the defect and asserts the
supported outcome. Keep each test focused on one coherent behavior; several
related assertions may be needed to establish that behavior.

### Fixtures, assertions and isolation

- Use JUnit Jupiter and AssertJ. Separate arrange, act and assert clearly; a short
  test does not need ceremonial comments. Name the behavior and condition in the
  method name or `@DisplayName`.
- Use minimal synthetic fixtures owned by the test in `src/test/resources` or
  `@TempDir`. Write outputs to temporary locations. Unit/domain tests do not
  read operator documents, runtime CSVs or production YAML. A contract test may
  intentionally read production resources when their configuration/format is the
  contract, and must make that purpose clear.
- Assert exact public values, diagnostic/result categories and meaningful state
  transitions. For rejection, check absence of forbidden side effects when that
  is the guarantee. An assertion that a call returned, a result is non-null or a
  mock was invoked is insufficient when value/state correctness matters.
- Use parameterized tests for independent cases of the same rule. Do not derive
  expected output through the same production helper or regenerate a golden
  fixture from the producer being tested.
- Use mocks/stubs at a dependency boundary when they isolate the behavior.
  Interaction assertions are appropriate for a contractual effect or ordering;
  asserting every private call couples the test to implementation.
- Prefer a small unit/component test for pure decisions, a shared TCK for an
  interchangeable port, and a deterministic integration test for real adapter,
  transaction, serialization or framework behavior. E2E tests connect important
  flows; they do not replace focused negative and boundary cases.

For example, this method from
[RecordLifecycleTest](../core/ioc-application/src/test/java/com/iocextractor/application/artifact/lifecycle/RecordLifecycleTest.java)
uses that class's `FIRST` and `POLICY` fixtures. It checks the meaning of an
expiry boundary with explicit times, without waiting for wall-clock time:

```java
@Test
void uses_a_half_open_active_interval() {
    RecordLifecycle lifecycle = RecordLifecycle.start(
            new LifecycleId(1), FIRST, POLICY.decide(FIRST));

    assertThat(lifecycle.isActiveAt(
            EffectiveTime.at(Instant.parse("2026-08-16T00:59:59.999Z")))).isTrue();
    assertThat(lifecycle.isDueAt(
            EffectiveTime.at(Instant.parse("2026-08-16T01:00:00Z")))).isTrue();
    assertThat(lifecycle.isDueAt(
            EffectiveTime.at(Instant.parse("2026-08-16T01:00:00.001Z")))).isTrue();
}
```

The neighboring tests check renewal identity and rejection of a due lifecycle.
Together these cases explain the contract; repeatedly calling a constructor or
asserting getters would not establish it.

Do not add automatic retries or hide intermittent failures. A flaky test needs
an owner, retained seed/failure evidence and a fix or explicit disposition.
Deterministic database, filesystem and framework integrations belong to ordinary
verification; provision only genuinely external dependencies explicitly.

## Test lifecycle

Class names determine Maven lifecycle ownership:

| Name | Owner | Phase |
|---|---|---|
| `Test*`, `*Test`, `*Tests`, `*TestCase` | Surefire | `test` |
| `IT*`, `*IT`, `*ITCase` | Failsafe | `integration-test`, then `verify` |

Every Failsafe suite also carries `@IntegrationTest`, `@EndToEndTest` or
`@ExternalTest`. Naming controls discovery; tags describe semantics. Do not
invent an undiscovered suffix such as `Should` or reclassify an integration
test as a unit test to get a faster or greener run.

Shared composed annotations live in `ioc-application-tck` under
`com.iocextractor.application.tck.junit`. Adapter/bootstrap test scopes may
depend on that module. Framework-free core modules use direct JUnit tags when
a test-support dependency would point outward.

The exact accepted suite counts live in
[test-lifecycle.properties](../build-support/test-quality/test-lifecycle.properties),
not in a second prose inventory. Adding, removing or reclassifying a suite requires
deliberate source/report-universe review and a same-change registry update.
Equal suite counts do not prove equivalent test quality.

## Tag taxonomy

Only these JUnit tags are accepted:

| Tag | Meaning |
|---|---|
| `integration` | Exercises a real database, filesystem, parser, serializer, framework context or transport implementation |
| `contract` | Verifies a reusable port, format, configuration or consumer contract |
| `architecture` | Enforces a structural, dependency or reference invariant |
| `e2e` | Covers a bounded workflow across multiple runtime boundaries; implies integration |
| `slow` | Identifies deliberately expensive evidence without changing lifecycle ownership |
| `external` | Requires explicitly provisioned infrastructure; implies integration |

Ordinary unit/component tests have no `unit` tag. Do not add feature, module,
author or temporary issue tags. A new cross-repository semantic needs a reviewed
taxonomy/verifier change. Abstract TCKs carry `@ContractTest`; a concrete
integration implementation uses both `@IntegrationTest` and `@ContractTest`.

## Coverage quality gate

### Floors and ratchets

The authoritative measurement is the complete local JaCoCo reactor aggregate
and its production-module groups. It includes downstream test execution.
Module-local HTML helps investigate a component, but may show less execution
than its aggregate group. Codecov is advisory.

Every production group and the reactor aggregate must satisfy both applicable
fixed floors and its accepted baseline:

| Scope | Line floor | Branch floor | Additional protection |
|---|---:|---:|---|
| Reactor production aggregate | 75% | 80% | Aggregate ratchet |
| `ioc-domain`, `ioc-application` | 85% | 90% | Per-module ratchet |
| Other production groups | No fixed floor | No fixed floor | Per-module ratchet |

[coverage-floors.tsv](../build-support/coverage-report/coverage-floors.tsv) owns
fixed floors; `none` means ratchet-only, not exempt.
[coverage-ratchets.tsv](../build-support/coverage-report/coverage-ratchets.tsv)
owns exact baseline counters and rationale. For every scope:

- line and branch ratios must not fall below their respective baseline ratios;
- absolute missed branches must not increase, even if a growing denominator
  improves the percentage;
- missed instructions must not increase where `absolute-instructions=enforced`;
  `observed` retains the number as review context.

Comparisons use exact integer fractions, not rounded HTML percentages. A new
counter after a zero-denominator baseline, or disappearance of a previously
nonzero counter, requires explicit review. The
[verifier mechanism](dev/build-quality.md#coverage) describes these checks.

[coverage-scope.tsv](../build-support/coverage-report/coverage-scope.tsv) owns
production/report membership. There are no manual class/package exclusions.
Test-support TCK, parent and report POMs are outside production denominators.
`ioc-platform-errors` is explicitly aggregate-only; lack of its local test JVM
does not remove its production bytecode from the aggregate.

### Reach the gate through useful tests

1. Start with the changed behavior and relevant contract cases, not a target
   number of test methods. Run the smallest suitable suite.
2. Open the affected group in the aggregate HTML after a complete run; use its
   source view and missed branch/instruction counters to locate untested decisions.
   Check report scope and freshness before concluding that a test is missing.
3. For each gap, identify a missing supported scenario: for example equality at
   expiry, a failed commit preserving state, a retry after committed delivery, or
   cancellation releasing an owned worker. State an independent expected result.
4. Add the smallest test at the owning layer that can observe that result.
   Check that assertions distinguish a plausible wrong result, a missing required
   state change or a broken invariant. A bug regression should reproduce
   the original failure.
5. Run focused tests, then `make verify` for the complete source/report union
   and aggregate policy. Inspect any changed missed counters, not just the
   headline percentage; run other controls required by the change.
6. Review residual uncovered behavior and test clarity. Accepted stable
   improvements update exact baseline counters and rationale in the same reviewed
   change, without loosening another protected metric.

A floor is a minimum safeguard, not a target to stop testing at. High coverage
does not prove assertion strength, complete failure/recovery cases, live transport
support or resource acceptance. Conversely, adding unrelated easy assertions to
offset an important untested path does not satisfy the test-quality policy.

Do not pad tests with repeated inputs, meaningless getter/constructor calls,
broad `isNotNull()` checks or assertions of private implementation just to raise
coverage. Do not delete required behavior, lower floors/baselines, add exclusions,
relabel reports or alter suite counts to make a failing build green. Legitimate
retirement/refactoring or module changes require a same-change scope, counter,
test and documentation review; the ratchet is not a ban on simplifying code.
Mutation diagnostics can help assess assertion strength, but do not replace
contract review or justify changing correct production behavior to kill a mutant.

## Integrity checks and reports

Root `validate` runs the JDK-only
[TestLifecycleVerifier](../build-support/test-quality/TestLifecycleVerifier.java).
It rejects unknown tags, invalid composed annotations, undiscovered/ambiguous
names, wrong lifecycle semantics, unguarded external suites, lifecycle filtering
and reviewed source-count drift.

Late `verify` in the coverage-report module checks the exact source/Surefire/
Failsafe report union. Missing, duplicate or wrong-owner reports fail.
Coverage validation separately reconciles scope/POM wiring, expected aggregate
groups, counter sums, local XML/HTML and execution data, then applies floors and
ratchets. A healthy percentage cannot compensate for incomplete evidence.

| Evidence | Location |
|---|---|
| Fast results | `*/target/surefire-reports/` |
| Integration/external-shell results | `*/target/failsafe-reports/` |
| Module coverage | `*/target/site/jacoco/` |
| Authoritative aggregate HTML/XML | `build-support/coverage-report/target/site/jacoco-aggregate/` |

When tests run, `initialize` deletes stale test reports, JaCoCo data and coverage
directories; the agent appends Surefire and Failsafe execution within that lifecycle.
A `-DskipTests` run preserves previous evidence and does not establish fresh test
coverage. Focused commands can replace only part of the reports; they cannot
qualify the complete reactor. CI retains available reports even after a late failure.

`make context` reports the recorded commit/worktree freshness. After production
Java or analyzer-scope changes, run focused tests followed by `make verify` and
`make pmd-analysis` on the final worktree. Both controls must be passed and fresh
for a complete local quality claim; PMD freshness is independent of coverage.

## Commands

Use the Maven Wrapper through the Make facade:

```bash
# Fast Surefire cohort across the reactor.
make test
make test-fast

# Deterministic Failsafe cohort plus skipped external shells unless provisioned.
make test-integration

# Complete reactor gate: both cohorts, reports and quality controls.
make verify

# One module and its upstream dependencies.
make test-module MODULE=core/ioc-domain
make test-integration-module MODULE=adapters/adapter-store-jdbc

# One class or method; lifecycle selection follows its name.
make test-one MODULE=core/ioc-domain TEST=DefaultIndicatorNormalizerTest#strips_trailing_semicolon
make test-one MODULE=core/ioc-application TEST=RecordLifecycleTest
make test-one MODULE=adapters/adapter-cli-picocli TEST=SpringCommandConstructionIT
```

`make test-integration` skips the fast cohort deliberately. Tag/selector-filtered
runs are diagnostics, not substitutes for the complete gate.

## Bounded waits and worker cleanup

Asynchronous, concurrency, filesystem and transport tests need two independent
bounds: a timed coordination primitive (latch, barrier, future, join or described
condition deadline), and a containing JUnit `@Timeout` safety boundary.

Offline wait-bearing suites normally use five seconds for coordination and
30 seconds for the suite. Release or cancel owned workers in `finally` or an
`AutoCloseable` fixture and assert bounded termination. Preserve interrupted
status when adapting `InterruptedException`; name the awaited event on timeout.

Short sleeps are acceptable only within an explicit polling deadline when no
coordination signal exposes the condition. The live SMB `CHANGE_NOTIFY` test's
deliberate wait beyond its request timeout is protocol evidence, not an offline
test pattern. See its [contract suite](../adapters/adapter-transport-smb/src/test/java/com/iocextractor/adapter/out/transport/smb/SmbChangeNotifyContractIT.java).

Surefire/Failsafe do not impose one fixed fork timeout. Forks can own several
suites and provisioned external work; local operation timeouts plus suite-level
bounds own the deadline. Reconsider a fork watchdog if a reproducible hang
survives those bounds; never conceal an unbounded primitive with retries.

## External and slow evidence

External suites stay Failsafe-discoverable and are disabled unless their explicit
provisioning system property is `true`:

| Property | Provisioned evidence |
|---|---|
| `ioc.smb.contract` | Live SMB managed-import and `CHANGE_NOTIFY` contracts |
| `ioc.smb.encryption.contract` | Effective authenticated-session encryption |
| `ioc.smb.hardening.contract` | Two-identity namespace/ACL hardening |
| `ioc.import.load.enabled` | Managed-import load profile |

An offline skip is not live evidence. Record target/capabilities without secrets,
executed counts, cleanup and unavailable checks or explicit dispositions.
`slow` alone does not disable or move a test. Do not let external requirements
silently enter the deterministic offline cohort.

Read credentials directly from environment, such as `SMB_USER`/`SMB_PASSWORD`
or `SMB_SERVICE_USER`/`SMB_SERVICE_PASSWORD`. Never forward them through `-D`:
Failsafe retains system properties in XML. Non-secret host/share/path/encryption
selectors identify the qualified target. The centralized external harness remains
[TOOL-4](KNOWN-ISSUES.md#9-developer-tooling--build-tool); current bring-your-own
fixtures do not imply automated provisioning or a broader support claim.

## Diagnostic pilots

Mutation and stability pilots are scheduled/manual diagnostics:

```bash
make mutation-pilot
make stability-pilot
make stability-pilot SEED=73 REPEAT=1
```

The domain-only `mutation-pilot` profile uses default PIT mutators and zero score
thresholds. Tool/test failures fail the command; survived mutants require semantic
triage, not automatic production changes. Reports live in
`core/ioc-domain/target/pit-reports/` with summaries under `target/test-pilots/`.
Expansion beyond domain is [TOOL-5](KNOWN-ISSUES.md#9-developer-tooling--build-tool).

The stability pilot runs all functional JAR modules sequentially, randomizing
Maven/JUnit order with the published seed and incrementing it per repetition.
It verifies the exact source/report union and archives each pass under
`target/test-pilots/stability/run-N-seed-S/`. The first failure stops execution
with its seed and partial reports; there is no automatic retry.

The [Test Diagnostics workflow](../.github/workflows/test-pilots.yml) retains
both bundles for 30 days. Neither pilot is an ordinary PR/release gate.

## Reference consumer contracts

Use independent reference consumers and exact synthetic golden fixtures for
supported external formats. They detect producer drift without reusing its
decoder; they do not prove acceptance by a named deployed consumer.

| Surface | Existing evidence |
|---|---|
| Mutable CSV projections | `GoldenPipelineIT`: all configured artifacts, exact UTF-8/semicolon/NULL/column-order/CRLF bytes, independent consumer |
| Immutable slices | `ReferenceArtifactConsumerTest`: complete v1/explicit-layout v2 fixtures and checksum/marker rejection; `OnDemandExportIntegrationIT`: actual local/published copies |
| CLI automation | Exact help/version/error/summary/completion fixtures; only the generated run ID is tokenized |
| ECS logging | `LogbackConfigurationIT`: exact deterministic JSON and independent JSON-pointer queries/types, with only build version substituted |
| Regex/configuration | Shared RE2/J and JDK contract plus bootstrap binding/selection and configured-pattern compatibility |

Fixtures remain with the owning modules' `src/test/resources/consumer/` where
applicable. Precise format and scenario contracts belong there and in the owning
capability docs.

Out-of-reactor library consumers are separate network-dependent tool fixtures.
They resolve parent-independent packaged artifacts with isolated settings/caches,
check selected repository origin and bundle bytes, and exercise actual guide
examples. They add no reactor suite or production-coverage scope. CI qualifies
all six cataloged components: four independent component consumers plus events
core/Spring adapter, joined by the `library-consumer` aggregate status. Its
existence does not prove external branch-protection settings or public publication.
See the [publication guide](guides/library-publication.md).

## Codecov reporting

CI revalidates the complete aggregate with `tools/ci/codecov.sh verify-input`
before retention. A separate reporting job downloads it, requires the expected
XML, then runs the pinned external uploader; it does not rerun tests.

| Informational context | Target | Tolerance |
|---|---:|---:|
| `codecov/project/absolute-floor` | 75% | 0% |
| `codecov/project/base-ratchet` | Base (`auto`) | 0.1% |
| `codecov/patch/changed-lines` | 90% | 0% |

[codecov.yml](../codecov.yml) makes all three informational. The patch percentage
is review information, not a local 90% changed-lines gate. Checks annotations are
enabled and mutable PR comments disabled.

OIDC permission belongs only to the reporting job. The full-SHA-pinned Action
and pinned CLI upload only the named aggregate XML; report discovery, plugins
and telemetry are disabled. Only the external Action is best-effort:
missing/invalid project-owned input fails its owning check, while an external
upload/status failure does not weaken Maven enforcement or block an otherwise
valid release.

## Update triggers

Update policy, applicable verifiers/registries and affected docs together when
changing test discovery, tags, external provisioning, report topology, coverage
collection/floors/ratchets or flake rules. Review the semantics of a suite-universe
change instead of updating a count silently. Changing measurements/durations
belong to retained execution evidence, not a prose inventory here.

Sources of truth:

- [Root POM](../pom.xml), [Makefile](../Makefile) and [CI workflow](../.github/workflows/ci.yml).
- [Test lifecycle registry/verifier/tests](../build-support/test-quality/README.md).
- [Coverage registry/verifier/tests](../build-support/coverage-report/README.md).
- [Build-quality mechanics](dev/build-quality.md) and [module boundaries](BOUNDARIES.md).
