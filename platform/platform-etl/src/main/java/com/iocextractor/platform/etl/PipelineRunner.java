package com.iocextractor.platform.etl;

import com.iocextractor.diagnostics.Diagnostic;
import com.iocextractor.diagnostics.DiagnosticException;
import com.iocextractor.diagnostics.DiagnosticFactory;
import com.iocextractor.diagnostics.codes.PipelineDiagnosticCodes;
import com.iocextractor.diagnostics.result.FailurePolicy;
import com.iocextractor.diagnostics.result.BoundedNotification;
import com.iocextractor.diagnostics.result.Notification;
import com.iocextractor.diagnostics.sink.DiagnosticSink;
import com.iocextractor.diagnostics.sink.NoopDiagnosticSink;

import java.time.Clock;
import java.util.List;
import java.util.Objects;

/**
 * Sequential runner for a pipeline.
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

    /** Creates a runner with an explicit per-run diagnostic retention budget. */
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
     * Runs the pipeline over the initial envelope.
     *
     * @param input initial envelope
     * @param pipeline pipeline to run
     * @param <I> initial payload type
     * @param <O> final payload type
     * @return final envelope
     */
    public <I, O> Envelope<O> run(Envelope<I> input, Pipeline<I, O> pipeline) {
        return runWithOutcome(input, pipeline).envelope();
    }

    /**
     * Runs the pipeline and returns its final envelope together with the typed
     * diagnostic summary maintained by the bounded accumulator.
     *
     * @param input initial envelope
     * @param pipeline pipeline to run
     * @param <I> initial payload type
     * @param <O> final payload type
     * @return typed pipeline outcome
     */
    public <I, O> PipelineRunResult<O> runWithOutcome(Envelope<I> input, Pipeline<I, O> pipeline) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(pipeline, "pipeline");

        var bounded = new BoundedNotification(maxDiagnosticsPerRun, diagnosticFactory);
        bounded.addAll(input.diagnostics());
        Envelope<?> current = compact(input, bounded.diagnostics());
        for (Stage<?, ?> stage : pipeline.stages()) {
            var stageInput = current.atStage(stage.name());
            try (var ignored = observer.openStage(stageInput.meta())) {
                observer.stageStarted(stageInput.meta());
                long startedAt = System.nanoTime();
                try {
                    Envelope<?> next = executeStage(stage, stageInput);
                    List<Diagnostic> delta = delta(stageInput.diagnostics(), next.diagnostics());
                    delta.forEach(diagnosticSink::emit);
                    bounded.addAll(delta);
                    current = compact(next, bounded.diagnostics());
                    rejectIfRequired(current.diagnostics());
                    observer.stageCompleted(stageInput.meta(), System.nanoTime() - startedAt);
                } catch (StageProcessingFailure failure) {
                    observer.stageFailed(stageInput.meta(), System.nanoTime() - startedAt, failure.propagated());
                    throw failure.propagated();
                } catch (RuntimeException ex) {
                    observer.stageFailed(stageInput.meta(), System.nanoTime() - startedAt, ex);
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
        current.diagnostics().stream()
                .filter(diagnostic -> diagnostic.code() == PipelineDiagnosticCodes.DIAGNOSTICS_SUPPRESSED)
                .forEach(diagnosticSink::emit);
        return new PipelineRunResult<>(cast(current), bounded.summary());
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

    private List<Diagnostic> delta(List<Diagnostic> before, List<Diagnostic> after) {
        if (after.size() < before.size() || !after.subList(0, before.size()).equals(before)) {
            throw new IllegalStateException("Stage diagnostics must be append-only");
        }
        return List.copyOf(after.subList(before.size(), after.size()));
    }

    private Envelope<?> compact(Envelope<?> envelope, List<Diagnostic> diagnostics) {
        return new Envelope<>(envelope.payload(), envelope.meta(), diagnostics);
    }

    private void rejectIfRequired(List<Diagnostic> diagnostics) {
        new Notification()
                .addAll(diagnostics)
                .throwIfRejected(failurePolicy);
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
