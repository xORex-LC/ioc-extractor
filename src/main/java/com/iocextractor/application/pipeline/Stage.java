package com.iocextractor.application.pipeline;

/**
 * One Pipes-and-Filters processing stage.
 *
 * @param <I> input payload type
 * @param <O> output payload type
 */
public interface Stage<I, O> {

    /**
     * Returns the stable stage identifier.
     *
     * @return stage name
     */
    StageName name();

    /**
     * Processes one envelope and returns a new envelope.
     *
     * @param input input envelope
     * @return output envelope
     */
    Envelope<O> process(Envelope<I> input);
}
