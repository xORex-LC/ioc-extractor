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
     * <p>A stage reports recoverable processing facts by appending diagnostics
     * to the returned envelope. A stopping failure is reported by throwing a
     * {@code DiagnosticException}; the same occurrence must never be both
     * attached and thrown.</p>
     *
     * @param input input envelope
     * @return output envelope
     */
    Envelope<O> process(Envelope<I> input);
}
