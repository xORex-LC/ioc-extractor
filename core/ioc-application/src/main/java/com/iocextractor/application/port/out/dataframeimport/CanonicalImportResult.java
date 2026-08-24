package com.iocextractor.application.port.out.dataframeimport;

import com.iocextractor.application.dataframeimport.model.ImportPromotionOutcome;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Idempotent result of one atomic cross-artifact canonical promotion.
 *
 * @param outcome newly committed or recovered from an existing receipt
 * @param acceptedRows accepted logical rows
 * @param rejectedRows rejected logical rows
 * @param publicMutations inserted/updated/cleared public rows
 * @param affectedArtifacts artifacts whose public revision advanced
 * @param observedArtifacts artifacts whose lifecycle deadline may have changed
 * @param projectionGenerations durable required generation by affected artifact
 * @param effectiveAt one transaction-wide lifecycle boundary
 */
public record CanonicalImportResult(
        ImportPromotionOutcome outcome,
        long acceptedRows,
        long rejectedRows,
        long publicMutations,
        Set<String> affectedArtifacts,
        Set<String> observedArtifacts,
        Map<String, Long> projectionGenerations,
        Instant effectiveAt) {

    /** Snapshots counts and affected artifacts. */
    public CanonicalImportResult {
        Objects.requireNonNull(outcome, "outcome");
        affectedArtifacts = Set.copyOf(Objects.requireNonNull(affectedArtifacts, "affectedArtifacts"));
        observedArtifacts = Set.copyOf(Objects.requireNonNull(observedArtifacts, "observedArtifacts"));
        projectionGenerations = Map.copyOf(Objects.requireNonNull(
                projectionGenerations, "projectionGenerations"));
        Objects.requireNonNull(effectiveAt, "effectiveAt");
        if (acceptedRows < 0 || rejectedRows < 0 || publicMutations < 0) {
            throw new IllegalArgumentException("Canonical import counts must not be negative");
        }
        if (!projectionGenerations.keySet().equals(affectedArtifacts)
                || projectionGenerations.values().stream().anyMatch(value -> value == null || value < 1)) {
            throw new IllegalArgumentException(
                    "Canonical import projection generations must cover exactly affected artifacts");
        }
    }
}
