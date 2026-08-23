package com.iocextractor.application.dataframeimport.model;

import com.iocextractor.application.artifact.CanonicalKeyMaterial;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * One primary or related artifact branch before live canonical matching.
 *
 * @param artifactName configured artifact name
 * @param role branch role
 * @param cells target columns with tri-state values
 * @param requestedSlot requested external slot on a primary branch when present
 * @param recordKey proposed canonical record key
 * @param matchKeys usable active-record match keys
 */
public record ImportArtifactBranch(
        String artifactName,
        ImportArtifactRole role,
        Map<String, ImportCell> cells,
        OptionalLong requestedSlot,
        Optional<CanonicalKeyMaterial> recordKey,
        List<CanonicalKeyMaterial> matchKeys) {

    /** Snapshots branch values and enforces positive requested slots. */
    public ImportArtifactBranch {
        Objects.requireNonNull(artifactName, "artifactName");
        Objects.requireNonNull(role, "role");
        cells = Map.copyOf(new LinkedHashMap<>(Objects.requireNonNull(cells, "cells")));
        requestedSlot = Objects.requireNonNull(requestedSlot, "requestedSlot");
        recordKey = Objects.requireNonNull(recordKey, "recordKey");
        matchKeys = List.copyOf(Objects.requireNonNull(matchKeys, "matchKeys"));
        if (artifactName.isBlank()) {
            throw new IllegalArgumentException("Import artifact name must not be blank");
        }
        if (requestedSlot.isPresent() && requestedSlot.getAsLong() < 1) {
            throw new IllegalArgumentException("Requested export slot must be positive");
        }
        if (role == ImportArtifactRole.RELATED && requestedSlot.isPresent()) {
            throw new IllegalArgumentException("Only the primary import branch may request an export slot");
        }
        if (matchKeys.stream().map(CanonicalKeyMaterial::definitionId).distinct().count() != matchKeys.size()) {
            throw new IllegalArgumentException("Import branch match-key definitions must be unique");
        }
    }

    /** Creates a branch before key material has been resolved. */
    public ImportArtifactBranch(String artifactName,
                                ImportArtifactRole role,
                                Map<String, ImportCell> cells,
                                OptionalLong requestedSlot) {
        this(artifactName, role, cells, requestedSlot, Optional.empty(), List.of());
    }
}
