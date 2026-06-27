package com.iocextractor.application.export;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/**
 * Named, ordered and indivisible set of artifacts emitted and delivered together.
 */
public record ExportProfile(String name, ExportMode mode, List<String> artifacts) {

    public ExportProfile {
        name = ExportArtifactSpec.requireText(name, "profile name");
        mode = Objects.requireNonNull(mode, "mode");
        artifacts = List.copyOf(Objects.requireNonNull(artifacts, "artifacts"));
        if (artifacts.isEmpty() || artifacts.stream().anyMatch(value -> value == null || value.isBlank())) {
            throw new IllegalArgumentException("Export profile artifacts must be non-empty names");
        }
        if (new HashSet<>(artifacts).size() != artifacts.size()) {
            throw new IllegalArgumentException("Export profile artifacts must be unique: " + name);
        }
    }
}
