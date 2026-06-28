package com.iocextractor.application.sync;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Transport-neutral request to publish one local directory as an atomic remote unit.
 */
public record PublishAtomicallyRequest(String endpoint,
                                       String remotePath,
                                       Path localDirectory,
                                       String commitMarkerName) {

    public PublishAtomicallyRequest {
        endpoint = requireText(endpoint, "endpoint");
        remotePath = requireText(remotePath, "remotePath");
        localDirectory = Objects.requireNonNull(localDirectory, "localDirectory");
        commitMarkerName = safeLeaf(commitMarkerName, "commitMarkerName");
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static String safeLeaf(String value, String name) {
        value = requireText(value, name);
        if (value.contains("/") || value.contains("\\") || value.equals(".") || value.equals("..")) {
            throw new IllegalArgumentException(name + " must be one safe path segment");
        }
        return value;
    }
}
