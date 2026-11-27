package com.iocextractor.application.port.out.artifact;

import com.iocextractor.application.artifact.DocumentRun;
import com.iocextractor.application.artifact.lifecycle.ObservationId;

import java.util.Optional;

/** Service-store coordination for registered documents. Canonical proof lives in dataframe storage. */
public interface DocumentRunLedger extends RunLedger {

    /** Finds the exact occurrence regardless of its current saga checkpoint. */
    Optional<DocumentRun> findDocument(ObservationId id);

    /** Creates one run or returns the matching existing run. Identity changes fail closed. */
    DocumentRun beginDocument(DocumentRun.Identity identity);

    /** Pins the first accepted plan before writers are admitted. Equivalent retries are idempotent. */
    DocumentRun pinPlan(DocumentRun run, DocumentRun.Plan plan);
}
