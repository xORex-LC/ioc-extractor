# Sequential pipeline contracts and execution

This package carries generic payloads and diagnostics through ordered stages.
It uses the JDK plus the shared diagnostics API and IOC diagnostic/error contracts.
The [module reference](../../../../../../../README.md) owns dependency and reuse
rules; [package-info.java](package-info.java) states the package boundary.

## Source map

| Source | Responsibility |
|---|---|
| [Envelope.java](Envelope.java) | Payload, metadata, copied diagnostic detail and exact observed summary |
| [EnvelopeMeta.java](EnvelopeMeta.java) | Run/source/stage correlation, timestamp and extension attributes |
| [StageId.java](StageId.java) | Nonblank stable stage identifier and initial-stage constant |
| [Stage.java](Stage.java) | One typed transformation with append-only diagnostics |
| [Pipeline.java](Pipeline.java) | Ordered stage list and declared payload-type composition |
| [PipelineRunner.java](PipelineRunner.java) | Calling-thread execution, compaction, delivery, policy and segment finalization |
| [PipelineRunResult.java](PipelineRunResult.java) | Final envelope and exact diagnostic summary |
| [PipelineObserver.java](PipelineObserver.java) | Synchronous run/stage scopes and operational callbacks |
| [NoopPipelineObserver.java](NoopPipelineObserver.java) | No-effect observer and no-op scope handles |
| [StageExecutionException.java](StageExecutionException.java) | Product unchecked wrapper used for primary checked observer-scope close failures |

## Composition, metadata and envelope copies

Use `Pipeline.start().then(...)` for compile-time payload chaining. The direct
record constructor accepts wildcard stages and checks neither adjacency nor
agreement with its declared input/output types. A mismatch can fail after earlier
stages have executed. Its stage list is copied and rejects nulls; stage objects
and captured resources remain shared.

Envelope payloads may be null. Metadata, diagnostics and summary are required;
diagnostic lists and metadata attribute maps are copied. Payloads, stage instances,
attribute values and diagnostic references are not deep-copied. Their owners decide
thread safety and cleanup. `StageId` rejects null/blank values without normalizing
them; run/source IDs are required but are not validated as nonblank identities.
`EnvelopeMeta.initial` uses an explicit clock and `StageId.INITIAL`; transitions
preserve the original creation time. Attribute updates replace matching keys.
Boolean lookup defaults for absent/non-Boolean values; string lookup stringifies
a present value and returns null when absent.

Use `withPayload`, `atStage` and metadata helpers to preserve diagnostic counts.
Appending a collection counts every occurrence; appending `DiagnosticBatch`
combines its retained sample with its full observed summary. The three-argument
`Envelope` constructor derives counts from a complete occurrence list. It cannot
reconstruct omitted counts from a compacted display list. Preserve the explicit
summary when carrying compacted detail, including across segment boundaries.
Public record constructors do not validate every cross-field relationship; runner
batch/delta validation checks the diagnostic evidence needed for execution.

## Runner checkpoints and diagnostic flow

The one/two-argument constructors use no-op delivery and a UTC diagnostic factory;
the one-argument form also supplies a no-op observer. The four-argument form accepts
explicit collaborators with the default detail budget; the full constructor also
accepts a positive budget. A run creates its own accumulator; no
worker pool, persistent state or transaction is created.

The runner seeds retained input diagnostics and exact counts, removing its own
`PIPELINE.DIAGNOSTICS_SUPPRESSED` display marker from real input occurrences.
Initial occurrences are not delivered again. Every stage then follows this order:

1. Set stage metadata, open its scope and call `stageStarted`.
2. Invoke the stage and require the existing diagnostic list prefix to remain equal.
3. Validate the new batch/count delta, merge it and synchronously deliver newly
   retained occurrences.
4. Compact the next envelope, evaluate policy over retained detail plus any marker,
   then call `stageCompleted` if accepted.
5. Close the stage scope while unwinding.

A stage must preserve exact counts and append its own diagnostics. High-cardinality
stages should use `BoundedDiagnosticCollector` with the runner's budget and reserved
marker code; matching budgets preserve the direct-run sample. Never append a
synthetic display marker as a new occurrence or reset the summary during a payload
copy. The shared [diagnostics result reference](../../../../../../../../platform-diagnostics-api/src/main/java/com/iocextractor/platform/diagnostics/result/README.md)
owns batch validation, replacement order, checked counts and partial merge behavior.

The budget covers retained ELEMENT/RUN detail. OPERATION is exempt; payload and
context size remain unbounded by it. Replacements can produce more delivery calls
than retained slots. Counts exclude synthetic markers, include omitted occurrences
and never deduplicate equal diagnostics. Returned outcomes expose exact counts
independently of the display list.

Built-in policies retain their stopping signals after compaction. Custom policies
see a reconstructed `Notification`, not all observed reasons; perform rules needing
every code before compaction. A null or throwing custom policy fails the call
rather than permitting the next stage. No policy is evaluated before the first
stage or for an empty chain. The caller owns safe effect placement and recovery.

## Exceptions and observation

A stage's `DiagnosticException` is delivered and propagated as the same exception.
Another runtime failure from `process` is converted to `PIPELINE.STAGE_FAILED`
with the stage, reason and original cause. Report an occurrence either in the
returned envelope or by throwing it, never both. Runtime failures in delta
validation, delivery, policy or observer completion are outside that stage-body
translation. No generic diagnostic conversion or exhaustive failure callback is
guaranteed for `Error`.

Callbacks run synchronously. `stageFailed` observes runtime failures from stage
execution, diagnostic processing, policy or `stageCompleted`; stage-name lookup,
scope opening, `stageStarted` and scope closing need not produce it. A distinct
runtime failure from `stageFailed` is suppressed onto the primary failure; its
`Error` is not contained. Scope handles use try-with-resources: secondary close
failures are suppressed, primary unchecked failures propagate, and primary checked
close failures are wrapped in `StageExecutionException`.

Duration uses elapsed monotonic nanoseconds after `stageStarted` returns; it includes
stage work, diagnostic processing/delivery and policy, not scope setup/cleanup.
Observer completion is neither durable completion evidence nor a transaction
boundary. Prefer non-throwing observers and a non-throwing/resilient diagnostic
sink; collaborator failures can otherwise abort the call after effects. Sharing
a runner requires safe collaborators as well as stages.

## Segments and terminal suppression delivery

| Entry point | Completion responsibility |
|---|---|
| `run` / `runWithOutcome` | Complete invocation; attempt terminal marker delivery when stage-loop execution unwinds |
| `runPreparation` | On successful execution, defer the marker to the owner; stage-loop runtime failure attempts delivery immediately |
| `finishPreparation` | Deliver the prepared envelope's retained marker, without running stages, evaluating policy or opening an observer scope |

Terminal delivery normally occurs after stage scopes and before the invocation's
run scope closes. Each invocation opens a separate run scope; it does not span
an asynchronous gap or another segment. Continue from the returned prepared
envelope to carry counts and avoid redelivery of retained preparation samples.
If no continuation follows, its owner calls `finishPreparation` once. Repeated
calls are not deduplicated and are not resource cancellation/cleanup.

A terminal sink runtime failure propagates on otherwise clean completion; when
another runtime failure is already propagating, a distinct delivery failure is
suppressed onto it. Reusing that same exception instance does not self-suppress.
Input/seeding failures can precede the terminal-delivery region. These guards do
not promise a final report for every failure or make a violating sink safe.

See the [test reference](../../../../../../test/java/com/iocextractor/platform/etl/README.md)
for current witnesses, the [application service](../../../../../../../../../core/ioc-application/src/main/java/com/iocextractor/application/service/IocExtractionService.java)
for owned workspace continuation, and [ARCH-5](../../../../../../../../../docs/KNOWN-ISSUES.md#arch-5) for the
separate contract review required before external reuse.
