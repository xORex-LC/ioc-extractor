# ETL kernel tests

Deterministic JUnit Jupiter/AssertJ tests exercise the framework-free kernel with
fixed clocks, synthetic stages, recording observers and collecting/resilient
sinks. No Spring, logging backend, database, network or credentials are required.
The [module reference](../../../../../../../README.md) owns dependency rules;
the [production reference](../../../../../../main/java/com/iocextractor/platform/etl/README.md)
owns the execution and lifetime map.

## Test map

| Source | Observable behavior |
|---|---|
| [EnvelopeTest.java](EnvelopeTest.java) | Clock-controlled correlation, metadata/payload transitions, copied immutable diagnostic detail and exact bounded counts across envelope helpers |
| [PipelineRunnerTest.java](PipelineRunnerTest.java) | Ordered execution and stage metadata; accepted/rejected and invalid custom policies; budgets/counts; append-only diagnostics; input/resumed detail; preparation continuation/finalization; typed/untyped stage failures; observer/sink failure preservation |

The two suites use Surefire naming, with no local Failsafe suites, resource
fixtures or external-service gates. Tests remain synchronous; they do not claim
thread safety for stage state or collaborators shared across concurrent calls.

`PipelineRunnerTest` distinguishes retained samples, exact observed counts and
sink delivery. It checks one terminal suppression attempt on completion/failure,
its position between stage and run scope closure, no redelivery of preparation
samples, and continued merging of omitted counts. Its parameterized preparation
case finalizes through either continuation or `finishPreparation`; calling both
is not a supported ownership pattern. The resilience case checks successful and
failed stage paths even when delegate and fallback reporting both fail.

Exception checks preserve the original stage diagnostic/cause, a distinct
secondary failure as suppressed, and the same exception instance without
self-suppression. Prefix checks reject removal, replacement or severity changes
of existing diagnostics. Invalid custom policy checks ensure no later stage runs.

## Running and coverage limits

From the repository root:

```bash
make test-module MODULE=platform/platform-etl
make test-one MODULE=platform/platform-etl TEST=PipelineRunnerTest
make verify
```

These local suites are behavior witnesses, not exhaustive qualification of every
public constructor or observer boundary. Direct wildcard type-chain validation,
compacted custom-policy semantics, seed FATAL/empty-chain policy timing,
post-effect callback/scope failures and resource ownership remain part of the
separate [ARCH-5 review](../../../../../../../../../docs/KNOWN-ISSUES.md#arch-5) before external reuse.
Documenting their current limits does not close that review.

Cross-module checks have separate owners:

- [ArtifactPolicyCheckpointTest](../../../../../../../../../core/ioc-application/src/test/java/com/iocextractor/application/pipeline/stage/ArtifactPolicyCheckpointTest.java)
  checks the application prepare→checkpoint→write arrangement, including rejection
  before canonical writes/ID reservation. The generic runner owns no rollback.
- [PipelineDiagnosticMdcTest](../../../../../../../../platform-diagnostics-logging/src/test/java/com/iocextractor/observability/diagnostics/PipelineDiagnosticMdcTest.java)
  checks the logging observer's run/stage correlation, terminal scope and concurrent
  run isolation outside this module.
- [ArchitectureTest](../../../../../../../../../bootstrap/ioc-app/src/test/java/com/iocextractor/ArchitectureTest.java)
  enforces the ETL package's inward/framework-free dependency rule.

Use [TESTING.md](../../../../../../../../../docs/TESTING.md) for lifecycle and coverage policy. Add regressions
for changed supported outcomes, counts, cleanup ordering and accepted-prefix
behavior without asserting private implementation steps. Update owning references,
public Javadoc and meaningful consumer tests with a contract change. Application
workspace lifecycle, durable recovery and final completion evidence belong to the
service and its tests, rather than these in-memory kernel checks.
