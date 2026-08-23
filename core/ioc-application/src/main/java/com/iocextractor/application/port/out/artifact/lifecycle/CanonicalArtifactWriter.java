package com.iocextractor.application.port.out.artifact.lifecycle;

import com.iocextractor.application.artifact.lifecycle.CanonicalArtifactConfirmation;
import com.iocextractor.application.artifact.lifecycle.LifecycleWriteResult;

/**
 * Driven transaction port for confirming prepared canonical artifact records.
 *
 * <p>An implementation must acquire storage write ownership, sample its injected
 * lifecycle time source exactly once, evaluate the configured record-validity policy, and then
 * atomically apply business rows, provenance, lifecycle facts, observation
 * commit marker, revision and projection generation. Replaying the same
 * observation/artifact pair returns its durable prior outcome without renewing
 * records or sampling a new effective time.
 */
public interface CanonicalArtifactWriter {

    /**
     * Confirms one prepared artifact under one durable observation identity.
     *
     * @param confirmation identity-resolved prepared records
     * @return committed or idempotently replayed transaction outcome
     */
    LifecycleWriteResult confirm(CanonicalArtifactConfirmation confirmation);
}
