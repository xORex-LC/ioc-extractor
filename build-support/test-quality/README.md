# Test lifecycle verification

## Purpose and structure

Repository-owned source-discovery and report-union integrity for Surefire and
Failsafe. These JDK-only tools are compiled by root AntRun; this directory is not
a Maven project and is not a production test/coverage scope.

| File | Responsibility |
|---|---|
| [TestLifecycleVerifier.java](TestLifecycleVerifier.java) | Check naming/tag/composed-annotation semantics, source counts, Maven wiring and late report union |
| [TestLifecycleVerifierTest.java](TestLifecycleVerifierTest.java) | Temporary synthetic reactors and negative discovery/tag/report mutations |
| [test-lifecycle.properties](test-lifecycle.properties) | Exact reviewed counts for fast, integration, external and deterministic-offline source suites |

## Source contract

Surefire owns `Test*`, `*Test`, `*Tests` and `*TestCase`; Failsafe owns `IT*`,
`*IT` and `*ITCase`. Names select lifecycle; tags describe semantics. Supported
tags and composed annotations are checked, including the integration meaning of
external/E2E annotations. Executable source recognition also covers ArchUnit and
inherited port-contract suites, rather than scanning only literal `@Test` uses.

External suites are a subset of integration suites. The deterministic-offline
count is fast plus integration minus external; do not add external twice.
Counts describe suites, not executed cases, parameterized invocations or quality
of assertions. A same-count replacement still needs semantic review.

The verifier protects the source-selection lifecycle and stale-output cleanup,
including preserving test/coverage outputs for `-DskipTests` analysis. Its source
inventory reads the repository convention, not an unrestricted Java parser or a
replacement for Maven/JUnit discovery.

## Early and late checks

Run from the repository root:

```bash
./mvnw -B -ntp -N validate
```

Root validation compiles the tool, runs its standalone synthetic harness and
checks the real source universe. It does not run the reactor's tests.

After full reactor Failsafe completion, the coverage report POM calls
`verify-reports`. Exactly one named XML suite must correspond to every source
suite in the correct engine; duplicate, missing, unexpected and cross-engine
reports fail. This check reconciles suite names/unions. Surefire/Failsafe own
case execution and test-failure enforcement. A report for a property-gated
external shell may legitimately contain only skips; its presence is not live
qualification.

Use `make verify` for complete deterministic offline evidence and `make context`
for its freshness. Update source counts only with an intentional suite-universe
change and matching verifier/evidence/documentation review. Do not relabel a test
or alter counts to hide a lost suite. [TESTING.md](../../docs/TESTING.md) owns test design,
tags, timed waits, external evidence and coverage requirements; the
[coverage owner](../coverage-report/README.md) owns the late union invocation.
