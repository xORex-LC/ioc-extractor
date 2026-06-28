package com.iocextractor.application.export;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Immutable public schema and identity metadata for one artifact in an export plan.
 */
public record ExportArtifactSpec(String artifactName,
                                 String fileName,
                                 List<String> columns,
                                 int identityEpoch,
                                 String identityHash,
                                 String schemaHash,
                                 String mappingHash) {

    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    public ExportArtifactSpec {
        artifactName = requireText(artifactName, "artifactName");
        fileName = requireLeafName(fileName);
        columns = List.copyOf(Objects.requireNonNull(columns, "columns"));
        if (columns.isEmpty() || columns.stream().anyMatch(column -> column == null || column.isBlank())) {
            throw new IllegalArgumentException("Export artifact columns must be non-empty names");
        }
        if (new HashSet<>(columns).size() != columns.size()) {
            throw new IllegalArgumentException("Export artifact columns must be unique: " + artifactName);
        }
        if (identityEpoch < 1) {
            throw new IllegalArgumentException("Export artifact identity epoch must be positive");
        }
        identityHash = requireSha256(identityHash, "identityHash");
        schemaHash = requireSha256(schemaHash, "schemaHash");
        mappingHash = requireSha256(mappingHash, "mappingHash");
    }

    static String requireSha256(String value, String field) {
        Objects.requireNonNull(value, field);
        if (!SHA256.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " must be a lower-case SHA-256 value");
        }
        return value;
    }

    static String requireText(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    private static String requireLeafName(String value) {
        String fileName = requireText(value, "fileName");
        if (fileName.contains("/") || fileName.contains("\\") || fileName.equals(".") || fileName.equals("..")) {
            throw new IllegalArgumentException("Export fileName must be a single relative path segment");
        }
        return fileName;
    }
}
