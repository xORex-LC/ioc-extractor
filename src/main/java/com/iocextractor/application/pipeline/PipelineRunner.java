package com.iocextractor.application.pipeline;

import com.iocextractor.diagnostics.result.FailurePolicy;
import com.iocextractor.diagnostics.result.Notification;

import java.util.Objects;

/**
 * Sequential runner for a pipeline.
 */
public final class PipelineRunner {

    private final FailurePolicy failurePolicy;
    private final PipelineObserver observer;

    /**
     * Creates a runner using the supplied failure policy.
     *
     * @param failurePolicy policy evaluated after each stage
     */
    public PipelineRunner(FailurePolicy failurePolicy) {
        this.failurePolicy = Objects.requireNonNull(failurePolicy, "failurePolicy");
        this.observer = new NoopPipelineObserver();
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
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(pipeline, "pipeline");

        Envelope<?> current = input;
        for (Stage<?, ?> stage : pipeline.stages()) {
            var stageInput = current.atStage(stage.name());
            try (var ignored = observer.openStage(stageInput.meta())) {
                observer.stageStarted(stageInput.meta());
                long startedAt = System.nanoTime();
                try {
                    current = apply(stage, stageInput);
                    new Notification()
                            .addAll(current.diagnostics())
                            .throwIfRejected(failurePolicy);
                    observer.stageCompleted(stageInput.meta(), System.nanoTime() - startedAt);
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
        return cast(current);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Envelope<?> apply(Stage stage, Envelope<?> input) {
        return stage.process(input);
    }

    @SuppressWarnings("unchecked")
    private <O> Envelope<O> cast(Envelope<?> envelope) {
        return (Envelope<O>) envelope;
    }
}
