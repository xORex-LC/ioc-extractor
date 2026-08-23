package com.iocextractor.application.artifact;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Resolves versioned record-key and match-key material from final artifact rows. */
public final class CanonicalArtifactKeyResolver {

    private final Map<String, ArtifactIdentityDefinition> definitions;

    /** Creates one immutable artifact-key catalog. */
    public CanonicalArtifactKeyResolver(List<ArtifactIdentityDefinition> definitions) {
        Objects.requireNonNull(definitions, "definitions");
        Map<String, ArtifactIdentityDefinition> indexed = new LinkedHashMap<>();
        for (ArtifactIdentityDefinition definition : definitions) {
            Objects.requireNonNull(definition, "definitions element");
            if (indexed.putIfAbsent(definition.artifactName(), definition) != null) {
                throw new IllegalArgumentException("Duplicate artifact key definition: " + definition.artifactName());
            }
        }
        this.definitions = Map.copyOf(indexed);
    }

    /** Resolves the immutable key for a newly created canonical record. */
    public Optional<CanonicalKeyMaterial> recordKeyOf(String artifactName, ArtifactRow row) {
        ArtifactIdentityDefinition definition = definitions.get(artifactName);
        return definition == null ? Optional.empty() : materialOf(definition.recordKey(), row);
    }

    /** Returns whether the artifact is governed by the versioned key catalog. */
    public boolean containsArtifact(String artifactName) {
        return definitions.containsKey(artifactName);
    }

    /** Resolves every usable alternative active-record match key in configured order. */
    public List<CanonicalKeyMaterial> matchKeysOf(String artifactName, ArtifactRow row) {
        ArtifactIdentityDefinition definition = definitions.get(artifactName);
        if (definition == null) {
            return List.of();
        }
        var materials = new ArrayList<CanonicalKeyMaterial>(definition.matchKeys().size());
        definition.matchKeys().forEach(match -> materialOf(match, row).ifPresent(materials::add));
        return List.copyOf(materials);
    }

    /** Resolves one named formula without any storage or framework dependency. */
    public static Optional<CanonicalKeyMaterial> materialOf(CanonicalKeyDefinition definition, ArtifactRow row) {
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(row, "row");
        String canonical = definition.mode() == CanonicalKeyMode.FIRST_NON_EMPTY
                ? firstNonEmpty(definition, row)
                : composite(definition, row);
        return Optional.ofNullable(canonical).map(value -> new CanonicalKeyMaterial(
                definition.definitionId(), ArtifactIdentityDefinition.sha256(value), value));
    }

    private static String firstNonEmpty(CanonicalKeyDefinition definition, ArtifactRow row) {
        for (String column : definition.columns()) {
            String value = normalize(row.value(column));
            if (value != null) {
                return "[" + CanonicalArtifactIdentityResolver.jsonString(column.toLowerCase(Locale.ROOT))
                        + "," + CanonicalArtifactIdentityResolver.jsonString(value) + "]";
            }
        }
        return null;
    }

    private static String composite(CanonicalKeyDefinition definition, ArtifactRow row) {
        StringBuilder json = new StringBuilder("[");
        boolean anyValue = false;
        for (int index = 0; index < definition.columns().size(); index++) {
            if (index > 0) {
                json.append(',');
            }
            String value = normalize(row.value(definition.columns().get(index)));
            if (value == null) {
                json.append("null");
            } else {
                anyValue = true;
                json.append(CanonicalArtifactIdentityResolver.jsonString(value));
            }
        }
        return anyValue ? json.append(']').toString() : null;
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() || "NULL".equalsIgnoreCase(trimmed) ? null : trimmed;
    }
}
