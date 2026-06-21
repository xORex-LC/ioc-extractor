package com.iocextractor.platform.etl;

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
     * @return stage id
     */
    StageId name();

    /**
     * Processes one envelope and returns a new envelope.
     *
     * @param input input envelope
     * @return output envelope
     */
    Envelope<O> process(Envelope<I> input);
}
