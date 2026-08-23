package com.iocextractor.application.dataframeimport.model;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;

/**
 * One primary or related artifact branch before live canonical matching.
 *
 * @param artifactName configured artifact name
 * @param role branch role
 * @param cells target columns with tri-state values
 * @param requestedSlot requested external slot on a primary branch when present
 */
public record ImportArtifactBranch(
        String artifactName,
        ImportArtifactRole role,
        Map<String, ImportCell> cells,
        OptionalLong requestedSlot) {

    /** Snapshots branch values and enforces positive requested slots. */
    public ImportArtifactBranch {
        Objects.requireNonNull(artifactName, "artifactName");
        Objects.requireNonNull(role, "role");
        cells = Map.copyOf(new LinkedHashMap<>(Objects.requireNonNull(cells, "cells")));
        requestedSlot = Objects.requireNonNull(requestedSlot, "requestedSlot");
        if (artifactName.isBlank()) {
            throw new IllegalArgumentException("Import artifact name must not be blank");
        }
        if (requestedSlot.isPresent() && requestedSlot.getAsLong() < 1) {
            throw new IllegalArgumentException("Requested export slot must be positive");
        }
        if (role == ImportArtifactRole.RELATED && requestedSlot.isPresent()) {
            throw new IllegalArgumentException("Only the primary import branch may request an export slot");
        }
    }
}
