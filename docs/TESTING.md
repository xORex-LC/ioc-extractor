# Testing

The repository has one lossless Maven test lifecycle. Surefire owns fast test
classes and Failsafe owns integration test classes; a full `verify` runs both
cohorts and is the authoritative deterministic offline gate.

## Test lifecycle

Class names determine Maven lifecycle ownership:

| Name | Owner | Phase |
|---|---|---|
| `Test*`, `*Test`, `*Tests`, `*TestCase` | Surefire | `test` |
| `IT*`, `*IT`, `*ITCase` | Failsafe | `integration-test`, then `verify` |

Every Failsafe suite must also carry integration semantics through
`@IntegrationTest`, `@EndToEndTest` or `@ExternalTest`. Naming controls Maven
discovery; tags describe test semantics. They are complementary and neither is
a substitute for the complete release gate.

The shared composed annotations live in `ioc-application-tck` under
`com.iocextractor.application.tck.junit`. Adapter and bootstrap test scopes may
depend on that module. Framework-free core modules use direct JUnit tags when a
test-support dependency would point in the wrong architectural direction.

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

Ordinary unit and component tests intentionally have no `unit` tag. Do not add
feature, module, author or temporary issue tags. Extend the accepted taxonomy
and its verifier deliberately if a new cross-repository semantic is needed.

Reusable abstract TCK classes carry `@ContractTest`; subclasses inherit the
tag. A concrete integration implementation of a TCK uses both
`@IntegrationTest` and `@ContractTest`.

## Commands

Use the Maven Wrapper through the Make facade:

```bash
# Fast Surefire cohort across the reactor.
make test
make test-fast

# Deterministic Failsafe cohort plus provisioned external suite shells.
make test-integration

# Complete release-quality reactor: both cohorts, reports and quality gates.
make verify

# One module and its upstream dependencies.
make test-module MODULE=core/ioc-domain
make test-integration-module MODULE=adapters/adapter-store-jdbc

# One class or method; the facade selects Surefire or Failsafe from its name.
make test-one MODULE=core/ioc-domain TEST=DefaultIndicatorNormalizerTest#strips_trailing_semicolon
make test-one MODULE=adapters/adapter-cli-picocli TEST=SpringCommandConstructionIT
```

`make test-integration` skips the fast cohort deliberately. It is a focused
developer command, not a replacement for `make verify`. Selecting tags manually
is diagnostic only and does not prove release completeness.

## External and slow evidence

External suites remain discoverable by Failsafe but are disabled unless their
explicit system property is `true`:

| Property | Provisioned evidence |
|---|---|
| `ioc.smb.contract` | Live SMB managed-import and `CHANGE_NOTIFY` contracts |
| `ioc.smb.encryption.contract` | Effective authenticated-session encryption |
| `ioc.smb.hardening.contract` | Two-identity namespace/ACL hardening contract |
| `ioc.import.load.enabled` | Managed-import load profile |

An external skip is not a pass. Release evidence must record whether the
provisioned suite ran or received an explicit disposition. External tests must
not silently enter the deterministic offline cohort. The `slow` tag alone does
not disable or move a test.

## Integrity checks and reports

Root `validate` runs the JDK-only verifier in `build-support/test-quality`. It
fails on unknown tags, invalid composed annotations, a test class outside both
Maven naming conventions, integration semantics owned by Surefire, a Failsafe
suite without integration semantics, an unguarded external suite, lifecycle
filtering or reviewed source-count drift.

At the end of a full reactor `verify`, the coverage-report module also compares
the source inventory with Surefire and Failsafe XML. Missing, stale, duplicate
or wrong-engine reports fail the build. The accepted source universe is kept in
`build-support/test-quality/test-lifecycle.properties`; it is an integrity
ratchet, not permission to replace one test with a weaker same-count test.

Generated evidence is written to:

- `*/target/surefire-reports/` for fast results;
- `*/target/failsafe-reports/` for integration and external-shell results;
- `*/target/site/jacoco/` for module coverage;
- `build-support/coverage-report/target/site/jacoco-aggregate/` for aggregate
  HTML/XML coverage.

When tests are enabled, the build deletes stale test reports, JaCoCo execution
data, module coverage directories and the aggregate coverage directory during
`initialize`. A `-DskipTests` analyzer/package run preserves the latest test
evidence. JaCoCo appends execution data from the Surefire and Failsafe JVMs
within the clean test lifecycle, so downstream integration/E2E execution is
retained in the aggregate. CI uploads test and coverage directories even when a
later step fails.

The aggregate report and its groups are the authoritative release measurement.
`build-support/coverage-report/coverage-scope.tsv` gives every reactor project
an explicit disposition. The production denominator is exactly 19 JAR modules;
the reusable TCK, root and build-only POMs are excluded, with no class or package
exclusions. Seventeen production modules must generate local XML/HTML reports.
`ioc-platform-errors` and `ioc-adapter-regex-re2j` have no local test JVM and are
declared aggregate-only, but their downstream execution must still appear as
aggregate groups.

`make verify` checks that this complete report topology exists and contains no
stale or unexpected evidence. It applies the aggregate and per-module baselines
in `coverage-ratchets.tsv`: line and branch ratios cannot fall, and missed branch
counts cannot rise. Ratios are compared as integer fractions rather than rounded
percentages. Small-denominator modules also reject an increase in missed
instructions; larger scopes retain that absolute value as review context.

The current ratchets prevent regression while aggregate/domain/application
fixed-floor gaps are remediated. They are not proof that the release floors have
been reached. Do not lower a baseline, add an exclusion or relabel a local report
to make a change pass. Review the changed behavior and tests; when a stable
improvement is accepted, update its exact counters and rationale in the same
change. Local JaCoCo remains authoritative when Codecov reporting is introduced.

## Test design and failure policy

Tests own their fixtures and outputs. Prefer synthetic data, `@TempDir`, bounded
waits and assertions on supported behavior. Contract tests may intentionally
read production resources when those resources are the public contract. A fixed
defect receives a regression test with positive, negative and boundary outcomes
appropriate to its risk.

Do not add automatic retries or hide intermittent failures. A flaky test needs
an owner, evidence and a fix or explicit disposition. Environment-dependent
behavior belongs behind an explicit external provision, while deterministic
database, filesystem and framework integrations remain part of ordinary
`verify`.

## Update triggers

Update this document and the lifecycle verifier when changing naming patterns,
tag semantics, Maven phase ownership, external provisioning, report locations,
coverage collection or flake policy. Update the mutable release evidence ledger
for measured counts and durations; do not copy those changing values here.
