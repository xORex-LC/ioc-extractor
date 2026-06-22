package com.iocextractor.adapter.out.sink.csv;

import com.iocextractor.application.aggregation.ArtifactRow;
import com.iocextractor.application.aggregation.ArtifactRowKey;
import com.iocextractor.application.port.out.aggregation.ArtifactIdentityResolver;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Artifact-schema aware row identity resolver. It belongs to the CSV adapter
 * because the key is derived from output artifact columns, not from the domain
 * IOC model.
 */
public final class ConfigurableArtifactIdentityResolver implements ArtifactIdentityResolver {

    private final Map<String, ArtifactKeyDefinition> definitions;

    public ConfigurableArtifactIdentityResolver(List<ArtifactKeyDefinition> definitions) {
        Map<String, ArtifactKeyDefinition> byName = new LinkedHashMap<>();
        for (ArtifactKeyDefinition definition : definitions) {
            byName.put(definition.artifactName(), definition);
        }
        this.definitions = Map.copyOf(byName);
    }

    @Override
    public Optional<ArtifactRowKey> keyOf(String artifactName, ArtifactRow row) {
        ArtifactKeyDefinition definition = definitions.get(artifactName);
        if (definition == null) {
            return Optional.empty();
        }
        if (definition.firstNonEmpty()) {
            return firstNonEmptyKey(definition, row);
        }
        return compositeKey(definition, row);
    }

    private Optional<ArtifactRowKey> firstNonEmptyKey(ArtifactKeyDefinition definition, ArtifactRow row) {
        for (String column : definition.columns()) {
            String value = normalize(row.value(column));
            if (value != null) {
                return Optional.of(new ArtifactRowKey(column.toLowerCase(Locale.ROOT) + ":" + value));
            }
        }
        return Optional.empty();
    }

    private Optional<ArtifactRowKey> compositeKey(ArtifactKeyDefinition definition, ArtifactRow row) {
        StringBuilder key = new StringBuilder();
        for (String column : definition.columns()) {
            String value = normalize(row.value(column));
            if (value == null) {
                return Optional.empty();
            }
            if (!key.isEmpty()) {
                key.append('|');
            }
            key.append(column.toLowerCase(Locale.ROOT)).append(':').append(value);
        }
        return Optional.of(new ArtifactRowKey(key.toString()));
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
}
