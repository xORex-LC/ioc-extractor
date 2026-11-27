package com.iocextractor.application.artifact;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/**
 * Durable reference to checkpoint-approved prepared rows for one document run.
 *
 * @param checksum SHA-256 of the workspace recovery manifest, bound to the run and prepared bytes
 * @param artifacts complete artifact catalog in preparation order, including artifacts with no rows
 */
public record DocumentPreparationSeal(String checksum, List<String> artifacts) {
    public DocumentPreparationSeal {
        Objects.requireNonNull(checksum, "checksum");
        artifacts = List.copyOf(artifacts);
        if (!checksum.matches("[a-f0-9]{64}") || artifacts.stream().anyMatch(String::isBlank)
                || new HashSet<>(artifacts).size() != artifacts.size()) {
            throw new IllegalArgumentException("Invalid document preparation seal");
        }
    }
}
