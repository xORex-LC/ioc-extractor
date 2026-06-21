package com.iocextractor.application.pipeline;

import com.iocextractor.diagnostics.result.FailurePolicy;
import com.iocextractor.diagnostics.result.Notification;

import java.util.Objects;

/**
 * Sequential runner for a pipeline.
 */
public final class PipelineRunner {

    private final FailurePolicy failurePolicy;

    /**
     * Creates a runner using the supplied failure policy.
     *
     * @param failurePolicy policy evaluated after each stage
     */
    public PipelineRunner(FailurePolicy failurePolicy) {
        this.failurePolicy = Objects.requireNonNull(failurePolicy, "failurePolicy");
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
            current = apply(stage, current);
            new Notification()
                    .addAll(current.diagnostics())
                    .throwIfRejected(failurePolicy);
        }
        return cast(current);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Envelope<?> apply(Stage stage, Envelope<?> input) {
        return stage.process(input.atStage(stage.name()));
    }

    @SuppressWarnings("unchecked")
    private <O> Envelope<O> cast(Envelope<?> envelope) {
        return (Envelope<O>) envelope;
    }
}
