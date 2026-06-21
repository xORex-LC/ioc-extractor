package com.iocextractor.platform.etl;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Type-safe ordered list of stages.
 *
 * @param stages ordered stages
 * @param <I> initial payload type
 * @param <O> final payload type
 */
public record Pipeline<I, O>(List<Stage<?, ?>> stages) {

    /**
     * Creates a pipeline with defensive stage copying.
     */
    public Pipeline {
        stages = List.copyOf(Objects.requireNonNull(stages, "stages"));
    }

    /**
     * Starts an empty pipeline.
     *
     * @param <I> initial payload type
     * @return empty pipeline
     */
    public static <I> Pipeline<I, I> start() {
        return new Pipeline<>(List.of());
    }

    /**
     * Returns a new pipeline with the supplied stage appended.
     *
     * @param stage stage to append
     * @param <N> next output type
     * @return pipeline ending with the supplied stage
     */
    public <N> Pipeline<I, N> then(Stage<O, N> stage) {
        var next = new ArrayList<Stage<?, ?>>(stages);
        next.add(Objects.requireNonNull(stage, "stage"));
        return new Pipeline<>(next);
    }
}
