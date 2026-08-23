package com.iocextractor.application.dataframeimport.model;

import java.time.Instant;
import java.util.Objects;

/**
 * Idempotent request to durably reserve one candidate's global claim position.
 *
 * @param deliveryId preallocated occurrence identity
 * @param sourceId source trust boundary
 * @param candidateToken adapter-stable candidate identity
 * @param detectedAt detection time used for audit only, never ordering
 */
public record ImportClaimReservation(
        ImportDeliveryId deliveryId,
        ImportSourceId sourceId,
        String candidateToken,
        Instant detectedAt) {

    /** Enforces reservation identity fields. */
    public ImportClaimReservation {
        Objects.requireNonNull(deliveryId, "deliveryId");
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(candidateToken, "candidateToken");
        Objects.requireNonNull(detectedAt, "detectedAt");
        if (candidateToken.isBlank()) {
            throw new IllegalArgumentException("Import candidate token must not be blank");
        }
    }
}
