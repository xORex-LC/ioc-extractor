package com.iocextractor.application.artifact;

import java.util.List;
import java.util.Objects;

/** Named, immutable and fingerprinted record-key or match-key formula. */
public record CanonicalKeyDefinition(String definitionId,
                                     CanonicalKeyMode mode,
                                     List<String> columns) {

    public CanonicalKeyDefinition {
        if (definitionId == null || definitionId.isBlank()) {
            throw new IllegalArgumentException("Canonical key definition id must not be blank");
        }
        Objects.requireNonNull(mode, "mode");
        columns = List.copyOf(Objects.requireNonNull(columns, "columns"));
        if (columns.isEmpty() || columns.stream().anyMatch(column -> column == null || column.isBlank())) {
            throw new IllegalArgumentException("Canonical key columns must be non-blank");
        }
        if (columns.stream().distinct().count() != columns.size()) {
            throw new IllegalArgumentException("Canonical key columns must be unique");
        }
    }

    /** Returns the stable formula fingerprint persisted by storage adapters. */
    public String fingerprint() {
        StringBuilder descriptor = new StringBuilder("[\"canonical-key:v1\",")
                .append(CanonicalArtifactIdentityResolver.jsonString(definitionId))
                .append(',')
                .append(CanonicalArtifactIdentityResolver.jsonString(mode.name()));
        columns.forEach(column -> descriptor.append(',')
                .append(CanonicalArtifactIdentityResolver.jsonString(column)));
        return ArtifactIdentityDefinition.sha256(descriptor.append(']').toString());
    }
}
