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

## Diagnostic pilots

Mutation and stability pilots are opt-in diagnostics, not ordinary PR or
release gates:

```bash
# Mutate the complete core/ioc-domain production scope with one sequential worker.
make mutation-pilot

# Run the complete functional test universe three times with seeds 42, 43 and 44.
make stability-pilot

# Reproduce or extend a stability observation explicitly.
make stability-pilot SEED=73 REPEAT=1
```

The `mutation-pilot` Maven profile exists only in `core/ioc-domain`. It uses the
default PIT mutators with zero score thresholds: a broken test or tool remains a
failure, while a survived mutant requires review rather than automatically
blocking a release. Stable HTML/XML reports are written to
`core/ioc-domain/target/pit-reports/`; the wrapper summary is written under
`target/test-pilots/`.

The `stability-pilot` profile randomizes Surefire/Failsafe class order and JUnit
class/method order from the same published seed. The wrapper advances the seed
by one for each repetition, runs all 20 functional JAR modules sequentially,
and verifies the exact source/report union after every pass. It archives each
pass under `target/test-pilots/stability/run-N-seed-S/` before the next Maven
lifecycle deletes current reports. The first failure stops the pilot and keeps
its seed and partial XML evidence; there is no automatic retry.

The scheduled/manual `Test Diagnostics` workflow publishes both pilot bundles
for 30 days. It is intentionally absent from push and pull-request triggers.
PIT and stability remain scheduled/manual diagnostics unless a later reviewed
decision adopts a narrower blocking policy. Evidence-driven PIT expansion to
other production modules is tracked separately as
[`TOOL-5`](KNOWN-ISSUES.md#9-developer-tooling--build-tool); it is not part of
the 0.3.0 mutation universe.

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

Live SMB credentials are read directly from environment pairs such as
`SMB_USER`/`SMB_PASSWORD` or `SMB_SERVICE_USER`/`SMB_SERVICE_PASSWORD`. Never
forward a password through `-D`: Failsafe serializes JVM system properties into
its XML reports, and CI retains those reports as release evidence. Non-secret
fixture selectors (`host`, `port`, `share`, `remotePath`, encryption policy)
remain explicit system properties so the qualified target can be identified.

This is currently a bring-your-own-fixture boundary rather than a centralized
environment-management platform. The capability-driven suite registry,
provisioning/lease lifecycle, exact executed-count gate, result classification,
evidence bundle and verified cleanup required before more external integration
families are added are tracked as [`TOOL-4`](KNOWN-ISSUES.md#9-developer-tooling--build-tool).
That follow-up does not turn an offline skip into live evidence or reopen an
already target-qualified run.

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
exclusions. Eighteen production modules must generate local XML/HTML reports.
`ioc-platform-errors` has no local test JVM and is declared aggregate-only, but
its downstream execution must still appear as an aggregate group.

`make verify` checks that this complete report topology exists and contains no
stale or unexpected evidence. It applies the aggregate and per-module baselines
in `coverage-ratchets.tsv`: line and branch ratios cannot fall, and missed branch
counts cannot rise. Ratios are compared as integer fractions rather than rounded
percentages. Small-denominator modules also reject an increase in missed
instructions; larger scopes retain that absolute value as review context.

The same project-owned late gate applies `coverage-floors.tsv`. Every aggregate
or production-module scope has an explicit entry: `none` means ratchet-only,
while the reactor aggregate enforces `75%` lines / `80%` branches and both
`ioc-domain` and `ioc-application` enforce `85%` / `90%`. Floor comparisons also
use exact integer fractions, so a rounded display value cannot make a failing
ratio pass. Ratchets still protect stronger existing results independently of
the fixed floors.

Do not lower a floor or baseline, add an exclusion or relabel a local report to
make a change pass. Review the changed behavior and tests; when a stable
improvement is accepted, update its exact counters and rationale in the same
change. Local JaCoCo remains authoritative; Codecov reporting is advisory.

## Codecov reporting

The successful CI build revalidates the complete aggregate with
`tools/ci/codecov.sh verify-input` before retaining it as an artifact. A separate
`Codecov advisory` job downloads that already verified evidence and requires the
expected aggregate XML to be present before invoking the external uploader. It
does not rerun tests or generate a second coverage universe.

Repository `codecov.yml` defines three informational status contexts:

| Context | Target | Tolerance | Scope |
|---|---:|---:|---|
| `codecov/project/absolute-floor` | `75%` | `0%` | Complete production aggregate |
| `codecov/project/base-ratchet` | Base (`auto`) | `0.1%` | Complete production aggregate |
| `codecov/patch/changed-lines` | `90%` | `0%` | Pull-request changed lines |

All three use Codecov informational mode and are not required merge or release
gates. GitHub Checks annotations are enabled, while the mutable PR comment is
disabled. Review still requires meaningful positive, negative and boundary
outcomes for changed behavior; a patch percentage is not a substitute.

The reporting job receives OIDC permission without exposing it to the Maven
build job. Its Action and CLI versions are pinned in the workflow. Automatic
report search, uploader plugins and telemetry are disabled, and the only
coverage payload named for upload is
`build-support/coverage-report/target/site/jacoco-aggregate/jacoco.xml`.

Missing, stale or malformed project-owned coverage evidence fails Maven or the
pre-upload handoff. The Codecov Action alone is `continue-on-error`: an outage,
authentication failure, rate limit or missing remote status remains visible as
an external reporting failure but cannot turn a failed local coverage check
green or block an otherwise valid release.

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

The regex adapter runs one shared fast contract against both the default RE2/J
and JDK fallback implementations. It verifies stable engine identity,
compile-once reuse and exact ordered half-open spans. A bootstrap contract binds
the live classpath configuration, verifies `ioc.engine=jdk` selection and runs
every configured IOC detector and source section marker against both engines.
Keep configured patterns RE2-compatible; the fallback is not permission to add
JDK-only look-around or back-references.

## Bounded waits and worker cleanup

Every asynchronous, concurrency, filesystem or transport test must have two
independent bounds:

- the coordination primitive itself uses a timed latch, barrier, future, join
  or condition-polling deadline with a description of the expected event;
- the containing wait-bearing suite uses a JUnit `@Timeout` safety boundary.

Offline wait-bearing suites normally use a five-second coordination bound and a
30-second suite bound. A test that owns a worker must release or cancel it from
`finally` or an `AutoCloseable` fixture, then assert bounded termination. Preserve
the interrupted status when adapting `InterruptedException`. A timeout failure
must name the awaited event; adding a retry is not a timeout fix.

Short sleeps remain acceptable only inside an explicit polling deadline when no
latch, future or callback exposes the condition. The live SMB `CHANGE_NOTIFY`
contract intentionally sleeps for three seconds to prove survival beyond its
two-second request timeout; its polling deadline is ten seconds and its suite
boundary is 60 seconds. These waits are protocol evidence, not a model for
offline tests.

Surefire and Failsafe do not set one fixed fork timeout for 0.3.0. A fork can own
multiple suites, including explicitly provisioned external work, so a single
process deadline would provide a less precise owner and could terminate valid
evidence. Local operation timeouts plus suite-level JUnit boundaries are the
authoritative policy. Reconsider a fork watchdog if a reproducible hang can
survive those bounds; do not use it to conceal an unbounded test primitive.

## Update triggers

Update this document and the lifecycle verifier when changing naming patterns,
tag semantics, Maven phase ownership, external provisioning, report locations,
coverage collection or flake policy. Update the mutable release evidence ledger
for measured counts and durations; do not copy those changing values here.
