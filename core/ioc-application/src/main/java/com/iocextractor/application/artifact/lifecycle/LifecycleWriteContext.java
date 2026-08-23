package com.iocextractor.application.artifact.lifecycle;

import java.util.Objects;

/**
 * Source-scoped facts required to commit prepared rows through record validity.
 * The context is created by a driving boundary after lifecycle admission.
 */
public record LifecycleWriteContext(ObservationId observationId,
                                    String sourceKey,
                                    ConfirmationReceiptContext receipt) {

    public LifecycleWriteContext {
        Objects.requireNonNull(observationId, "observationId");
        Objects.requireNonNull(sourceKey, "sourceKey");
        Objects.requireNonNull(receipt, "receipt");
        if (sourceKey.isBlank()) {
            throw new IllegalArgumentException("Source key must not be blank");
        }
    }
}
