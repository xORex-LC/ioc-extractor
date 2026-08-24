package com.iocextractor.application.dataframeimport.model;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Safe dataframe-owned proof used to resume post-commit finalization.
 *
 * @param deliveryId committed occurrence
 * @param acceptedRows accepted logical rows
 * @param rejectedRows rejected logical rows
 * @param publicMutations inserted, updated or cleared public rows
 * @param affectedArtifacts artifacts whose public revision advanced
 * @param issues bounded value-free row rejection evidence
 */
public record ImportCommitEvidence(
        ImportDeliveryId deliveryId,
        long acceptedRows,
        long rejectedRows,
        long publicMutations,
        Set<String> affectedArtifacts,
        List<ImportRowIssue> issues) {

    /** Snapshots bounded evidence and validates aggregate counts. */
    public ImportCommitEvidence {
        Objects.requireNonNull(deliveryId, "deliveryId");
        affectedArtifacts = Set.copyOf(Objects.requireNonNull(affectedArtifacts, "affectedArtifacts"));
        issues = List.copyOf(Objects.requireNonNull(issues, "issues"));
        if (acceptedRows < 0 || rejectedRows < 0 || publicMutations < 0) {
            throw new IllegalArgumentException("Import commit evidence counts must not be negative");
        }
    }

    /** Derives the only valid post-commit terminal outcome. */
    public ImportTerminalOutcome terminalOutcome() {
        if (rejectedRows == 0) {
            return ImportTerminalOutcome.SUCCEEDED;
        }
        return acceptedRows == 0
                ? ImportTerminalOutcome.REJECTED
                : ImportTerminalOutcome.COMPLETED_WITH_ERRORS;
    }
}
