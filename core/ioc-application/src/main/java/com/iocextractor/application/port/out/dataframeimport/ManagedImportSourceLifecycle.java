package com.iocextractor.application.port.out.dataframeimport;

import com.iocextractor.application.dataframeimport.model.ImportSourceCandidate;
import com.iocextractor.application.dataframeimport.model.ImportSourceId;

import java.time.Instant;
import java.util.List;

/**
 * Driven port for transport-specific exclusive claim, immutable local
 * materialization and terminal source disposition. Implementations fail closed
 * when ownership or content consistency cannot be proven.
 */
public interface ManagedImportSourceLifecycle {

    /**
     * Performs one complete source listing and returns only stable eligible candidates.
     * Change notifications call the same method and never supply trusted filenames.
     *
     * @param sourceId configured source trust boundary
     * @param observedAt detection time
     * @return deterministic candidate order
     */
    List<ImportSourceCandidate> detect(ImportSourceId sourceId, Instant observedAt);

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
