# LIB-4: sequential pipeline reuse assessment

Status: analysis complete; owner agrees to defer extraction pending a concrete consumer need.
Date: 2026-09-15. Baseline: `edca8d696423d7c2b1fd266666a37fa5f5108286`.
Task branch: `r030-etl-analysis`, created from the current remote release branch.
This is an exploratory worknote, not an accepted library design or admission.

## Recommendation and owner context

Keep `platform-etl` internal for now. It contains a cohesive reusable execution
mechanism, but a second service needing that mechanism has not been identified.
Working with data does not by itself establish demand for a pipeline library.
The owner explicitly doubts broad applicability and asks to assess existing
instruments before committing to extraction.

The useful unit is synchronous execution of an ordered chain with consistent
stage diagnostics, stop decisions and observation. It does not supply reusable
business transformations. Do not split out trivial `Stage`/`StageId` wrappers as
separate artifacts, build a new workflow framework, or migrate existing flows
merely to manufacture consumers.

Diagnostics, configuration and logging retain their separate proposals. This
review does not reopen their owner decisions. Work remains analysis-only; no
production changes, publication, commits, push or merge are part of this task.
Merge requires an explicit owner instruction; use one logical task per branch.

## Evidence and actual consumers

Read the module's eleven production Java files (including `package-info`), its
two test classes and POM; the diagnostics model/policy/accumulator; IOC pipeline
assembly; logging observer; processed-import preparer; architecture tests;
processing documentation; ADR-0017 and the existing library proposals.

| Evidence | Finding |
|---|---|
| [Module API](../../../../platform/platform-etl/src/main/java/com/iocextractor/platform/etl/README.md) | Generic stages, envelopes, runner and observer; no IOC payloads or Spring imports |
| [IOC assembly](../../../../core/ioc-application/src/main/java/com/iocextractor/application/service/IocExtractionService.java), lines 98–104, 126–175 | Only production pipeline assembly/runner construction found; eight stages |
| [Logging observer](../../../../platform/platform-observability/src/main/java/com/iocextractor/observability/logging/LoggingPipelineObserver.java) | Second direct consuming module, but an integration for the same IOC flow, not a second processing use case |
| [Processed import](../../../../adapters/adapter-csv/src/main/java/com/iocextractor/adapter/in/csv/CsvProcessedImportRowPreparer.java) | Reuses refanging, extraction, classification and mapping without the generic runner |
| [Processing contract](../../../dev/processing.md) | Stage order and prepare/checkpoint/commit semantics belong to the application |
| [Inventory](../evidence/shared-code-inventory.md) | LIB-4 already deferred until consumer and dependency-closure review |

Production imports of `com.iocextractor.platform.etl` occur in `ioc-application`
and `platform-observability`. Oneshot and daemon invoke the same extraction use
case; they do not establish independent pipeline semantics. Test references in
CSV, diagnostics-logging and bootstrap are integration evidence, not additional
production consumers. Framework-free boundaries are covered by the existing
`ArchitectureTest.platform_etl_is_generic_and_framework_free` rule.

## What the existing code actually provides

| Instrument | Existing behavior | Service responsibility |
|---|---|---|
| `Pipeline.start().then(...)` | Builds an ordered chain, checking adjacent payload types through the fluent API | Stage implementation and ordering |
| `Stage<I,O>` | Receives one envelope and returns an envelope with another payload type | Reading, parsing, validation, conversion, filtering and writing |
| `Envelope<T>` | Carries payload, metadata and diagnostics; helpers append findings | Payload design and safe ownership |
| `EnvelopeMeta`, `StageId` | Run/source/stage correlation, timestamp, extension attributes | Identity meaning, attribute names/types; source need not be a file |
| `PipelineRunner` | Runs each stage synchronously on the calling thread | Invocation, isolation between tasks, scheduling and concurrency |
| `FailurePolicy` integration | Evaluates retained diagnostics after each completed stage; rejection throws | Appropriate rule and pre-side-effect checkpoint placement |
| `DiagnosticSink` integration | Emits each newly appended stage diagnostic before policy evaluation | Nonthrowing delivery adapter and output |
| Bounded accumulation | Limits retained ELEMENT/RUN occurrences, retains severity signals and adds suppression summary | Per-stage memory, OPERATION occurrence volume, sink volume |
| `PipelineObserver` | Run/stage scopes, start/completion/failure hooks and elapsed duration | Logging/metrics implementation and callback reliability |
| `PipelineRunResult` | Final envelope plus diagnostic counts on successful return | Product completion status and recovery on exceptions |

The bound does not cap payload size, diagnostics generated inside one stage or
sink traffic. OPERATION diagnostics bypass the retained-item budget. Repeated
single-diagnostic appends copy lists; large batches should not assume streaming
or constant-memory processing. There is no performance measurement here.

The run protocol is:

```text
open run scope
  for each stage:
    set stage metadata -> open stage scope -> notify start
    execute stage -> validate append-only diagnostics -> emit delta
    accumulate/compact -> evaluate policy -> notify completion
    close stage scope
  emit terminal suppression summary, including on runtime failure
close run scope
```

A thrown `DiagnosticException` stops execution even under collect-and-continue.
That policy continues only when a stage returns recoverable diagnostics with a
usable output. A generic stage `RuntimeException` becomes `PIPELINE.STAGE_FAILED`
with its original cause. This is not automatic recovery from arbitrary errors.
The runner owns observer scopes, not resources carried inside payloads.

No built-in item iteration, chunk transactions, retries, resume/checkpoints,
branching/joining, parallel stages, timeouts, cancellation protocol, resource
payload cleanup, network connectors or distributed delivery is provided. A
caller can put a stream or external call inside a stage, but must own its entire
lifecycle. Envelope/map immutability is shallow; concurrent reuse is conditional
on the supplied stages, policies, sinks, observers and payloads being safe.

## Review findings, ordered by extraction impact

No critical production incident was established by this review. The high-impact
findings below concern the promised external API and supported custom consumers;
current IOC assembly uses the fluent API and built-in severity policies.

### High: retention changes custom stop decisions

[PipelineRunner](../../../../platform/platform-etl/src/main/java/com/iocextractor/platform/etl/PipelineRunner.java),
lines 137–141 and 196–199, evaluates policy after compaction. A legitimate pure
policy that stops on a selected code/context or count can miss suppressed input.
The sink sees that input; policy does not. Changing only the retention budget can
therefore decide whether the next stage executes.

Local reproduction: one stage appends two WARN diagnostics with distinct
`reason` values (`normal`, `block`). A pure policy stops on `reason=block`.
With budget 1 the next stage runs; with sufficient budget the same policy stops.
Built-in ERROR/FATAL policies preserve their severity signals through the
accumulator; this experiment is not evidence of current IOC data loss.

Before promising custom policies in a library, either define a deliberately
limited supported policy contract or separate decision input from retained
reporting. A stateful decision API or unlimited retention is not automatically
the right fix. No design choice is made in this analysis.

### High: public construction bypasses the type-safe chain

[Pipeline](../../../../platform/platform-etl/src/main/java/com/iocextractor/platform/etl/Pipeline.java),
lines 14–20, is a public record accepting `List<Stage<?, ?>>`. Its type parameters
are not verified by that constructor. For example:

```java
Pipeline<String, Integer> forged = new Pipeline<>(List.of());
Integer value = runner.run(stringEnvelope, forged).payload();
```

This compiles without raw types or unchecked warnings, then throws
`ClassCastException` when reading the result. Incompatible nonempty chains can
also bypass `then`. Current IOC wiring uses `start().then(...)` correctly.
Before extraction, encapsulate construction if type safety remains an API
promise; unchecked casts inside a sound builder are not themselves the problem.

### High: append-only validation accepts a severity replacement

The runner's prefix comparison (lines 185–189) uses `Diagnostic.equals`.
[Diagnostic](../../../../platform/platform-diagnostics/src/main/java/com/iocextractor/diagnostics/Diagnostic.java),
lines 100–108, compares code/context but ignores severity, cause and timestamp.
A stage replacing an inherited WARN with an equal-code/context FATAL passes the
append-only guard; compaction restores the old WARN and loses the replacement.

This requires a stage violating the intended append-only convention. It is a
failure of its validation, not evidence that current IOC stages do this.
[LIB-2](lib-2-diagnostics-design.md) already proposes severity-sensitive equality;
coordinate with that decision and define whether prefix preservation concerns
values or exact occurrences. Equality alone does not enforce every occurrence
invariant. Do not introduce a second diagnostics model to work around this.

### Medium: observer failures and successful side effects

Runner lines 115–120 and 132–156 allow scope/callback failures to abort a run.
`stageStarted` fails before the inner failure-observation block; `stageCompleted`
is called before scope close. A later close failure can therefore follow a
completion event and successful side effects. A run-scope close failure can
prevent return of the computed result. `PipelineObserver` has no nonthrowing
contract, unlike `DiagnosticSink`.

Specify these semantics before external use. Completion currently means stage
body plus policy, not durable commitment or successful scope cleanup. Retry of
such a failure is not automatically safe. Primary runtime failures are preserved
when failure observation or ordinary resource close also fails; that existing
protection should be retained. Current bootstrap supplies a resilient diagnostic
sink; arbitrary throwing delegates violate its documented port contract.

### Medium: arbitrary exception text is not sanitized by the runner

Runner lines 175–180 and 228–231 copy exception messages into diagnostic `reason`
and retain the cause. The IOC logging observer also logs the failure throwable.
A stage whose exception contains a credential can send it to the rendering/log
boundary. This is a conditional exposure path, not a discovered leaked secret.
Explicit masking helpers in LIB-3 do not make arbitrary exception messages safe.
Keep safe error construction and rendering responsibility explicit; this review
does not implement a universal sanitizer or change the logging policy.

### Contract clarifications, not established production defects

- Input diagnostics seed retained state but are not delivered as new stage
  occurrences. Policy first runs after stage one; an empty chain never evaluates
  it. A seeded FATAL therefore does not prevent stage one's side effects. This
  matches the documented after-stage timing; current IOC supplies an empty seed.
- Policy after a side-effecting stage cannot undo the effect. IOC deliberately
  separates preparation from writing. ADR-0017's 2026-07-15 clarification keeps
  the hook after the terminal stage; built-in policies cannot reject its
  WARN-or-lower projection diagnostics. This is not a general commit barrier.
- A failed run throws rather than returning a partial `PipelineRunResult`.
  Diagnostic delivery and stage-local reports must not be advertised as durable
  run history or an externally queryable job state.
- Null payloads are accepted; null stage results/names need explicit failure
  behavior. `Stage.name()` promises stability, but the runner does not enforce
  it. Metadata attributes are untyped and stages can replace correlation metadata. Do not infer global type or identity
  enforcement from defensive collection copying.

## SOLID and dependency boundary

`Stage`, `FailurePolicy`, `PipelineObserver` and `DiagnosticSink` provide useful
extension points. The runner's orchestration is cohesive; splitting each action
into a new interface would add complexity without an identified consumer need.
New stage implementations do not require editing the runner. The main
substitution problem is a seemingly valid custom policy receiving lossy input.
The public pipeline constructor weakens the type invariant independently of SOLID.

Current Maven/runtime direction:

```text
ioc-application ----------> platform-etl --> platform-diagnostics --> platform-errors
platform-observability --> platform-etl --------------------------> platform-errors
```

The module is an ordinary Java 21 JAR, not a Boot executable. No external runtime
library is declared directly, but the project-owned closure is not standalone.
`StageExecutionException` extends `IocExtractorException`. The runner creates
catalog-bound `PipelineDiagnosticCodes`, throws local `DiagnosticException`,
calls `Notification.throwIfRejected`, and detects the suppression code by identity.

The [diagnostics proposal](lib-2-diagnostics-design.md) keeps product throwing
policy local and makes suppression construction explicit. ETL extraction must
reconcile those decisions, including who owns runner failures and generic code
metadata. The [logging proposal](lib-3-logging-design.md) keeps
`LoggingPipelineObserver` local; it must not move back into the generic logging
library just because ETL might be extracted.

If demand is later confirmed, prefer one cohesive runner module using the
admitted diagnostics API and an agreed exception/code boundary. Keep IOC stages,
payloads, metadata keys, observers, ledgers and transaction semantics local. Do
not yet admit coordinates or freeze API names. Retain the owner-selected lockstep
version policy. Admission requires a resolvable published dependency closure,
independent consumer, sources/Javadoc and component-specific publication checks.
The current [publication tooling](../../../guides/library-publication.md) targets
concurrency; `tools/ci/library-publication.py` fixes that artifact and rejects
POM dependencies. It cannot publish ETL unchanged.

## Alternatives and actual reason to reuse

These are capability comparisons, not a proposal to replace existing code.
Primary documentation was checked on 2026-09-15.

| Need | Existing alternative | Implication for LIB-4 |
|---|---|---|
| A few typed transformations | [JDK Function.andThen](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/function/Function.html) composes functions and propagates failures | Ordering alone is insufficient added value |
| Spring message-handler composition | [Spring Integration](https://docs.spring.io/spring-integration/reference/chain.html) provides handler chains; its documentation points Java users to `IntegrationFlow` | Consider existing messaging machinery when the flow is already adapter-owned; do not add Spring dependencies to IOC core |
| Chunked read/process/write with commit boundaries | [Spring Batch step configuration](https://docs.spring.io/spring-batch/reference/step/chunk-oriented-processing/configuring.html) supplies chunk processing with repository/transaction integration | ETL extraction does not supply these capabilities; compare the real requirement before building them |
| Ordered local stages sharing diagnostics and observation conventions | Existing `platform-etl` | Plausible added value if another service actually wants the same conventions |

A potential example is parsing an already acquired feed, validating entries,
normalizing accepted records and preparing an output. The library would run and
observe the stages; the service would still implement each transformation and
own persistence/delivery. This is an illustration, not a confirmed future design.
For remote device setup and reconciliation, this runner contributes only local
stage order; it does not own endpoint state, safe retries or recovery.

## Verification and next decision

Executed on the recorded baseline:

- `make doctor-core`: passed, Java 21.
- `make test-module MODULE=platform/platform-etl`: passed; 16 ETL tests and
  34 upstream diagnostics tests, zero failures/errors/skips.
- A temporary JDK-only probe under `/tmp/ioc-etl-review-probe` compiled with
  `java com.sun.tools.javac.Main -Xlint:unchecked -Werror` against the three
  freshly built module class directories. It reproduced the constructor hole,
  WARN-to-FATAL prefix replacement, custom-policy retention/control comparison,
  and seeded-FATAL after-stage timing (five observations). It is not committed,
  an independent published consumer, or a new test suite.
- Existing tests cover order, severity policies, suppression, ordinary scope
  order, typed/generic stage failure and preservation of selected primary errors.
  They do not qualify the proposed external consumer contracts above.

No production Java/build/analyzer scope changed. Full `make verify`,
`make pmd-analysis` and watchlist were not run; existing analyzer evidence remains
stale and no claim of new/drift-free analyzer findings is made. Documentation
validation is recorded in the discussion checkpoint after execution.

Disposition remains **deferred-second-consumer**. There is enough evidence to
avoid a premature extraction plan. Revisit when another concrete flow needs the
shared diagnostic/observation protocol, then resolve the high-impact seams and
qualify a small independent consumer before deciding on publication.

Owner disposition: no corresponding operations are currently expected in
adjacent services; defer ETL extraction. The owner is interested in existing
data-processing tools with specific value beyond established public libraries.
That is a separate screening task, not an extension of this runner proposal.
