package com.iocextractor.platform.etl;

import com.iocextractor.platform.diagnostics.Diagnostic;
import com.iocextractor.diagnostics.DiagnosticException;
import com.iocextractor.platform.diagnostics.DiagnosticFactory;
import com.iocextractor.diagnostics.codes.PipelineDiagnosticCodes;
import com.iocextractor.platform.diagnostics.result.FailurePolicy;
import com.iocextractor.platform.diagnostics.result.BoundedNotification;
import com.iocextractor.platform.diagnostics.result.DiagnosticBatch;
import com.iocextractor.platform.diagnostics.result.Notification;
import com.iocextractor.platform.diagnostics.sink.DiagnosticSink;
import com.iocextractor.platform.diagnostics.sink.NoopDiagnosticSink;

import java.time.Clock;
import java.util.List;
import java.util.Objects;

/**
 * Runs stages sequentially on the calling thread with a diagnostic-detail budget.
 *
 * <p>Initial diagnostics seed the accumulator without being redelivered as new
 * stage occurrences. Each stage must preserve the input diagnostic prefix and
 * exact observed counts. Newly retained occurrences are delivered before policy
 * evaluation, then the next stage receives a compacted envelope. The result's
 * summary retains exact counts, while custom {@link FailurePolicy} implementations
 * inspect a notification built from retained details and any suppression marker,
 * not the complete diagnostic history.
 *
 * <p>The retention budget limits {@code ELEMENT}/{@code RUN} details.
 * {@code OPERATION} occurrences bypass that budget and remain visible, so it is
 * not a total diagnostic-count or heap bound. Suppression markers are summaries,
 * not additional observed occurrences.
 *
 * <p>Policy is evaluated after each stage, not before the first stage. A fatal
 * diagnostic in the input therefore does not prevent the first stage from
 * running; an empty pipeline does not evaluate policy. A policy rejection can
 * follow a stage's externally visible effects. The runner does not start a
 * transaction, roll back effects, retry work or own a timeout/cancellation protocol.
 *
 * <p>{@link PipelineObserver} callbacks and scope cleanup are synchronous and
 * are not a general failure-isolation boundary. They can fail before execution
 * or after stage effects have completed. A stage's {@link DiagnosticException}
 * is delivered and propagated; another {@link RuntimeException} from the stage
 * body is converted to {@code PIPELINE.STAGE_FAILED}. An {@link Error} is not
 * translated into a diagnostic or guaranteed a failure callback or terminal
 * suppression delivery. Supply the required non-throwing {@link DiagnosticSink}
 * and keep stage resource/recovery ownership outside the runner.
 */
public final class PipelineRunner {

    private static final int DEFAULT_MAX_DIAGNOSTICS_PER_RUN = 10_000;

    private final FailurePolicy failurePolicy;
    private final PipelineObserver observer;
    private final DiagnosticSink diagnosticSink;
    private final DiagnosticFactory diagnosticFactory;
    private final int maxDiagnosticsPerRun;

    /**
     * Creates a runner using the supplied failure policy.
     *
     * @param failurePolicy policy evaluated after each stage
     */
    public PipelineRunner(FailurePolicy failurePolicy) {
        this.failurePolicy = Objects.requireNonNull(failurePolicy, "failurePolicy");
        this.observer = new NoopPipelineObserver();
        this.diagnosticSink = NoopDiagnosticSink.INSTANCE;
        this.diagnosticFactory = new DiagnosticFactory(Clock.systemUTC());
        this.maxDiagnosticsPerRun = DEFAULT_MAX_DIAGNOSTICS_PER_RUN;
    }

    /**
     * Creates a runner using the supplied failure policy and observer.
     *
     * @param failurePolicy policy evaluated after each stage
     * @param observer operational observer
     */
    public PipelineRunner(FailurePolicy failurePolicy, PipelineObserver observer) {
        this.failurePolicy = Objects.requireNonNull(failurePolicy, "failurePolicy");
        this.observer = Objects.requireNonNull(observer, "observer");
        this.diagnosticSink = NoopDiagnosticSink.INSTANCE;
        this.diagnosticFactory = new DiagnosticFactory(Clock.systemUTC());
        this.maxDiagnosticsPerRun = DEFAULT_MAX_DIAGNOSTICS_PER_RUN;
    }

    /**
     * Creates a runner with explicit policy, observer and diagnostic delivery.
     *
     * @param failurePolicy policy evaluated after each stage
     * @param observer operational observer
     * @param diagnosticSink non-throwing diagnostic delivery port
     * @param diagnosticFactory factory for generic stage-failure diagnostics
     */
    public PipelineRunner(FailurePolicy failurePolicy,
                          PipelineObserver observer,
                          DiagnosticSink diagnosticSink,
                          DiagnosticFactory diagnosticFactory) {
        this(failurePolicy, observer, diagnosticSink, diagnosticFactory, DEFAULT_MAX_DIAGNOSTICS_PER_RUN);
    }

    /**
     * Creates a runner with an explicit per-run diagnostic retention budget.
     *
     * @param failurePolicy policy evaluated against compacted details after each stage
     * @param observer synchronous operational callbacks and scope handles
     * @param diagnosticSink non-throwing diagnostic delivery port
     * @param diagnosticFactory factory for generic failures and suppression markers
     * @param maxDiagnosticsPerRun positive ELEMENT/RUN detail budget; OPERATION
     *                             occurrences are outside it; not a payload or heap limit
     * @throws IllegalArgumentException if the retention budget is not positive
     */
    public PipelineRunner(FailurePolicy failurePolicy,
                          PipelineObserver observer,
                          DiagnosticSink diagnosticSink,
                          DiagnosticFactory diagnosticFactory,
                          int maxDiagnosticsPerRun) {
        this.failurePolicy = Objects.requireNonNull(failurePolicy, "failurePolicy");
        this.observer = Objects.requireNonNull(observer, "observer");
        this.diagnosticSink = Objects.requireNonNull(diagnosticSink, "diagnosticSink");
        this.diagnosticFactory = Objects.requireNonNull(diagnosticFactory, "diagnosticFactory");
        if (maxDiagnosticsPerRun < 1) {
            throw new IllegalArgumentException("maxDiagnosticsPerRun must be positive");
        }
        this.maxDiagnosticsPerRun = maxDiagnosticsPerRun;
    }

    /**
     * Runs the pipeline over the initial envelope, with the same failure behavior
     * as {@link #runWithOutcome(Envelope, Pipeline)}.
     *
     * @param <I> initial payload type
     * @param <O> final payload type
     * @param input initial envelope
     * @param pipeline pipeline to run
     * @return final envelope
     */
    public <I, O> Envelope<O> run(Envelope<I> input, Pipeline<I, O> pipeline) {
        return runWithOutcome(input, pipeline).envelope();
    }

    /**
     * Runs the pipeline and returns its compacted final envelope and exact
     * diagnostic summary.
     *
     * <p>An empty pipeline retains the input payload and compacts its diagnostics
     * without evaluating failure policy. Once the stage loop is entered, normal
     * completion and runtime failure attempt delivery of the terminal suppression
     * marker, if present. This does not make observer or sink failures harmless
     * after completed effects.
     *
     * @param <I> initial payload type
     * @param <O> final payload type
     * @param input initial envelope
     * @param pipeline pipeline to run
     * @return typed pipeline outcome
     */
    public <I, O> PipelineRunResult<O> runWithOutcome(Envelope<I> input, Pipeline<I, O> pipeline) {
        return runPart(input, pipeline, true);
    }

    /**
     * Runs a segment while deferring terminal suppression delivery on success.
     *
     * <p>The caller must either continue with {@link #runWithOutcome(Envelope, Pipeline)}
     * using the returned envelope or call {@link #finishPreparation(Envelope)}
     * once when abandoning that continuation. Runtime failure from the stage loop
     * attempts terminal summary delivery immediately. Calling this method does
     * not itself make the supplied stages side-effect-free or establish a
     * transaction boundary.
     *
     * @param <I> declared initial payload type
     * @param <O> declared final payload type
     * @param input initial envelope
     * @param pipeline segment to execute in order
     * @return compacted prepared envelope and exact diagnostic counts
     */
    public <I, O> PipelineRunResult<O> runPreparation(Envelope<I> input, Pipeline<I, O> pipeline) {
        return runPart(input, pipeline, false);
    }

    /**
     * Delivers the retained suppression marker when a segment has no continuation.
     *
     * <p>This does not cancel work, close stage resources, evaluate policy or open
     * a new observer scope. Calls are not deduplicated; the owner must finalize
     * diagnostic delivery once rather than also continuing the same preparation.
     *
     * @param prepared envelope returned by a successfully prepared segment
     */
    public void finishPreparation(Envelope<?> prepared) {
        emitSuppressionSummary(Objects.requireNonNull(prepared, "prepared").diagnostics(), null);
    }

    private <I, O> PipelineRunResult<O> runPart(Envelope<I> input, Pipeline<I, O> pipeline,
                                               boolean complete) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(pipeline, "pipeline");

        try (var ignored = observer.openRun(input.meta())) {
            return executeInRunScope(input, pipeline, complete);
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new StageExecutionException("Failed to close pipeline run scope", ex);
        }
    }

    private <I, O> PipelineRunResult<O> executeInRunScope(Envelope<I> input, Pipeline<I, O> pipeline, boolean complete) {
        var bounded = new BoundedNotification(maxDiagnosticsPerRun, diagnosticFactory, PipelineDiagnosticCodes.DIAGNOSTICS_SUPPRESSED);
        bounded.addAll(DiagnosticBatch.of(input.diagnostics().stream()
                .filter(diagnostic -> diagnostic.code() != PipelineDiagnosticCodes.DIAGNOSTICS_SUPPRESSED).toList(),
                input.diagnosticSummary(), PipelineDiagnosticCodes.DIAGNOSTICS_SUPPRESSED));
        Envelope<?> current = compact(input, bounded);
        RuntimeException runFailure = null;
        try {
            for (Stage<?, ?> stage : pipeline.stages()) {
                var stageInput = current.atStage(stage.name());
                try (var ignored = observer.openStage(stageInput.meta())) {
                    observer.stageStarted(stageInput.meta());
                    long startedAt = System.nanoTime();
                    try {
                        Envelope<?> next = executeStage(stage, stageInput);
                        DiagnosticBatch delta = delta(stageInput, next);
                        retainAndEmit(delta, bounded);
                        current = compact(next, bounded);
                        rejectIfRequired(current.diagnostics());
                        observer.stageCompleted(stageInput.meta(), System.nanoTime() - startedAt);
                    } catch (StageProcessingFailure failure) {
                        observeStageFailure(stageInput.meta(), startedAt, failure.propagated());
                        throw failure.propagated();
                    } catch (RuntimeException ex) {
                        observeStageFailure(stageInput.meta(), startedAt, ex);
                        throw ex;
                    }
                } catch (RuntimeException ex) {
                    // Stage/policy failures (already reported above) and any unchecked
                    // failure from closing the observer scope propagate unchanged.
                    throw ex;
                } catch (Exception ex) {
                    // Only a checked exception from AutoCloseable.close() reaches here.
                    throw new StageExecutionException("Failed to close stage scope: " + stage.name(), ex);
                }
            }
            return new PipelineRunResult<>(cast(current), bounded.summary());
        } catch (RuntimeException ex) {
            runFailure = ex;
            throw ex;
        } finally {
            if (complete || runFailure != null) { emitSuppressionSummary(current.diagnostics(), runFailure); }
        }
    }

    private Envelope<?> executeStage(Stage<?, ?> stage, Envelope<?> input) {
        try {
            return apply(stage, input);
        } catch (DiagnosticException exception) {
            diagnosticSink.emit(exception.diagnostic());
            throw new StageProcessingFailure(exception);
        } catch (RuntimeException exception) {
            Diagnostic diagnostic = diagnosticFactory.create(PipelineDiagnosticCodes.STAGE_FAILED)
                    .with("stage", stage.name().value())
                    .with("reason", reason(exception))
                    .cause(exception)
                    .build();
            diagnosticSink.emit(diagnostic);
            throw new StageProcessingFailure(new DiagnosticException(diagnostic));
        }
    }

    private void retainAndEmit(DiagnosticBatch diagnostics, BoundedNotification bounded) {
        bounded.addAll(diagnostics, diagnosticSink::emit);
    }

    private DiagnosticBatch delta(Envelope<?> input, Envelope<?> output) {
        List<Diagnostic> before = input.diagnostics();
        List<Diagnostic> after = output.diagnostics();
        if (after.size() < before.size() || !after.subList(0, before.size()).equals(before)) {
            throw new IllegalStateException("Stage diagnostics must be append-only");
        }
        return DiagnosticBatch.of(after.subList(before.size(), after.size()),
                output.diagnosticSummary().since(input.diagnosticSummary()), PipelineDiagnosticCodes.DIAGNOSTICS_SUPPRESSED);
    }

    private Envelope<?> compact(Envelope<?> envelope, BoundedNotification bounded) {
        return new Envelope<>(envelope.payload(), envelope.meta(), bounded.diagnostics(), bounded.summary());
    }

    private void rejectIfRequired(List<Diagnostic> diagnostics) {
        var notification = new Notification().addAll(diagnostics);
        var decision = Objects.requireNonNull(failurePolicy.evaluate(notification), "failure decision");
        if (decision.shouldStop()) {
            throw new DiagnosticException(decision.diagnostic()
                    .orElseThrow(() -> new IllegalStateException("Stop decision requires diagnostic")));
        }
    }

    private void emitSuppressionSummary(List<Diagnostic> diagnostics, RuntimeException runFailure) {
        try {
            diagnostics.stream()
                    .filter(diagnostic -> diagnostic.code() == PipelineDiagnosticCodes.DIAGNOSTICS_SUPPRESSED)
                    .findFirst()
                    .ifPresent(diagnosticSink::emit);
        } catch (RuntimeException deliveryFailure) {
            // A sink violating the non-throwing delivery contract must not
            // replace the pipeline failure as the reported cause of the run.
            if (runFailure == null) {
                throw deliveryFailure;
            }
            if (deliveryFailure != runFailure) {
                runFailure.addSuppressed(deliveryFailure);
            }
        }
    }

    private void observeStageFailure(EnvelopeMeta meta, long startedAt, RuntimeException stageFailure) {
        try {
            observer.stageFailed(meta, System.nanoTime() - startedAt, stageFailure);
        } catch (RuntimeException observationFailure) {
            if (observationFailure != stageFailure) {
                stageFailure.addSuppressed(observationFailure);
            }
        }
    }

    private String reason(RuntimeException exception) {
        return exception.getMessage() == null || exception.getMessage().isBlank()
                ? exception.getClass().getSimpleName()
                : exception.getMessage();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Envelope<?> apply(Stage stage, Envelope<?> input) {
        return stage.process(input);
    }

    @SuppressWarnings("unchecked")
    private <O> Envelope<O> cast(Envelope<?> envelope) {
        return (Envelope<O>) envelope;
    }

    private static final class StageProcessingFailure extends RuntimeException {

        private final DiagnosticException propagated;

        private StageProcessingFailure(DiagnosticException propagated) {
            super(propagated);
            this.propagated = propagated;
        }

        private DiagnosticException propagated() {
            return propagated;
        }
    }
}
