package com.iocextractor.application.port.in.ingest;

import com.iocextractor.application.artifact.lifecycle.ObservationId;

/** Preparation of an already claimed document without canonical writes. */
@FunctionalInterface
public interface PrepareIngestionUseCase {
    /** Indicates that retries must reconcile a durable canonical plan before considering rejection. */
    default boolean hasPromotionPlan(ObservationId id) { return false; }

    /** Closes any preparation run known to have no canonical effect after rejection intent is durable. */
    default void rejectedBeforePromotion(ObservationId id) { }

    PreparedIngestion prepare(IngestSourceCommand command);
}
