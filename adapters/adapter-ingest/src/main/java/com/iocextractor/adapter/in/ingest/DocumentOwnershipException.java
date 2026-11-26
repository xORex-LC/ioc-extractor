package com.iocextractor.adapter.in.ingest;

import com.iocextractor.application.ingest.admission.DocumentExecutionState.Failure;

/** An ownership check failed with a code safe for the journal and operational output. */
final class DocumentOwnershipException extends IllegalStateException {
    private final Failure failure;

    DocumentOwnershipException(Failure failure) {
        super(failure.name());
        this.failure = failure;
    }

    Failure failure() { return failure; }
}
