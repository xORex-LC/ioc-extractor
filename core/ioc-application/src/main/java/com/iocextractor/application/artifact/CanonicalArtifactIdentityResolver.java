package com.iocextractor.application.artifact;

import com.iocextractor.application.port.out.artifact.ArtifactIdentityResolver;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Canonical row-key resolver for dataframe storage.
 *
 * <p>The row key is {@code SHA-256} over a canonical JSON array. Nulls are
 * encoded explicitly, and first-non-empty identities include the selected column
 * name to avoid cross-column hash collisions.
 */
public final class CanonicalArtifactIdentityResolver implements ArtifactIdentityResolver {

    private final Map<String, ArtifactIdentityDefinition> definitions;

    public CanonicalArtifactIdentityResolver(List<ArtifactIdentityDefinition> definitions) {
        Map<String, ArtifactIdentityDefinition> byName = new LinkedHashMap<>();
        for (ArtifactIdentityDefinition definition : definitions) {
            byName.put(definition.artifactName(), definition);
        }
        this.definitions = Map.copyOf(byName);
    }

    @Override
    public Optional<ArtifactRowKey> keyOf(String artifactName, ArtifactRow row) {
        ArtifactIdentityDefinition definition = definitions.get(artifactName);
        if (definition == null) {
            return Optional.empty();
        }
        String canonical = definition.firstNonEmpty()
                ? firstNonEmptyJson(definition, row)
                : compositeJson(definition, row);
        if (canonical == null) {
            return Optional.empty();
        }
        return Optional.of(new ArtifactRowKey(ArtifactIdentityDefinition.sha256(canonical)));
    }

    private String firstNonEmptyJson(ArtifactIdentityDefinition definition, ArtifactRow row) {
        for (String column : definition.columns()) {
            String value = normalize(row.value(column));
            if (value != null) {
                return "[" + jsonString(column.toLowerCase(Locale.ROOT)) + "," + jsonString(value) + "]";
            }
        }
        return null;
    }

    private String compositeJson(ArtifactIdentityDefinition definition, ArtifactRow row) {
        StringBuilder json = new StringBuilder("[");
        boolean anyValue = false;
        for (int i = 0; i < definition.columns().size(); i++) {
            if (i > 0) {
                json.append(',');
            }
            String value = normalize(row.value(definition.columns().get(i)));
            if (value == null) {
                json.append("null");
            } else {
                anyValue = true;
                json.append(jsonString(value));
            }
        }
        json.append(']');
        return anyValue ? json.toString() : null;
    }

    private String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty() || "NULL".equalsIgnoreCase(trimmed)) {
            return null;
        }
        return trimmed;
    }

    static String jsonString(String value) {
        StringBuilder json = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> json.append("\\\"");
                case '\\' -> json.append("\\\\");
                case '\b' -> json.append("\\b");
                case '\f' -> json.append("\\f");
                case '\n' -> json.append("\\n");
                case '\r' -> json.append("\\r");
                case '\t' -> json.append("\\t");
                default -> {
                    if (c < 0x20) {
                        json.append("\\u%04x".formatted((int) c));
                    } else {
                        json.append(c);
                    }
                }
            }
        }
        return json.append('"').toString();
    }
}
