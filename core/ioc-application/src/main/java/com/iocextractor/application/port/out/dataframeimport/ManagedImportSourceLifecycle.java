package com.iocextractor.application.port.out.dataframeimport;

/**
 * Driven port for transport-specific exclusive claim, immutable local
 * materialization and terminal source disposition. Implementations fail closed
 * when ownership or content consistency cannot be proven.
 */
public interface ManagedImportSourceLifecycle {

    /**
     * Obtains ownership and returns exact durable local snapshot evidence.
     *
     * @param command reserved occurrence to claim
     * @return immutable snapshot evidence
     */
    ClaimImportSourceResult claim(ClaimImportSourceCommand command);

    /**
     * Completes idempotent archive/quarantine after terminal business outcome.
     *
     * @param command terminal disposition request
     */
    void disposition(DispositionImportSourceCommand command);
}
