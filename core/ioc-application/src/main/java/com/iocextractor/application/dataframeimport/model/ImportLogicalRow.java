package com.iocextractor.application.dataframeimport.model;

import java.util.List;
import java.util.Objects;

/**
 * Atomic source-row unit and all deterministic artifact branches.
 *
 * @param sourceRowNumber one-based physical CSV record number
 * @param branches primary and optional related branches accepted/rejected together
 */
public record ImportLogicalRow(long sourceRowNumber, List<ImportArtifactBranch> branches) {

    /** Enforces one primary branch and snapshots branches. */
    public ImportLogicalRow {
        if (sourceRowNumber < 1) {
            throw new IllegalArgumentException("Import source row number must be positive");
        }
        branches = List.copyOf(Objects.requireNonNull(branches, "branches"));
        long primaryCount = branches.stream()
                .filter(branch -> branch.role() == ImportArtifactRole.PRIMARY)
                .count();
        if (primaryCount != 1) {
            throw new IllegalArgumentException("Import logical row requires exactly one primary branch");
        }
    }
}
