package com.iocextractor.application.port.out.dataframeimport;

import com.iocextractor.application.dataframeimport.model.ImportPromotionOutcome;

import java.util.Set;
import java.util.Objects;

/**
 * Idempotent result of one atomic cross-artifact canonical promotion.
 *
 * @param outcome newly committed or recovered from an existing receipt
 * @param acceptedRows accepted logical rows
 * @param rejectedRows rejected logical rows
 * @param publicMutations inserted/updated/cleared public rows
 * @param affectedArtifacts artifacts whose public revision advanced
 */
public record CanonicalImportResult(
        ImportPromotionOutcome outcome,
        long acceptedRows,
        long rejectedRows,
        long publicMutations,
        Set<String> affectedArtifacts) {

    /** Snapshots counts and affected artifacts. */
    public CanonicalImportResult {
        Objects.requireNonNull(outcome, "outcome");
        affectedArtifacts = Set.copyOf(Objects.requireNonNull(affectedArtifacts, "affectedArtifacts"));
        if (acceptedRows < 0 || rejectedRows < 0 || publicMutations < 0) {
            throw new IllegalArgumentException("Canonical import counts must not be negative");
        }
    }
}
