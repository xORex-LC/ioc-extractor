package com.iocextractor.platform.etl;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Ordered stage composition with declared input and output payload types.
 *
 * <p>{@link #start()} and {@link #then(Stage)} connect declared payload types at
 * compile time. The direct record constructor accepts wildcard stages and does
 * not verify adjacency or consistency with {@code I}/{@code O}. Callers using
 * that constructor, raw types or unchecked casts own those checks; a mismatch
 * can fail during execution after earlier stages have already run.
 *
 * <p>The stage list is an immutable defensive copy. Stage instances and their
 * captured resources are not copied or made thread-safe. An empty composition
 * is supported; it does not introduce a stage-level failure-policy checkpoint.
 *
 * @param <I> declared initial payload type
 * @param <O> declared final payload type
 * @param stages ordered stage instances; copied, with no null elements
 */
public record Pipeline<I, O>(List<Stage<?, ?>> stages) {

    /**
     * Copies the stage list without validating the stages' payload-type chain.
     *
     * @param stages ordered stage instances; not null and containing no nulls
     * @throws NullPointerException if the list or an element is null
     */
    public Pipeline {
        stages = List.copyOf(Objects.requireNonNull(stages, "stages"));
    }

    /**
     * Starts an empty composition whose declared input and output types agree.
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
