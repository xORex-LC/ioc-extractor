package com.iocextractor.application.sync;

import java.util.List;
import java.util.Objects;

/**
 * Configured read-only remote source scanned by a fetch cycle.
 */
public record RemoteFetchSource(String sourceId,
                                String endpoint,
                                String remotePath,
                                List<String> include,
                                List<String> exclude) {

    public RemoteFetchSource {
        sourceId = requireText(sourceId, "sourceId");
        endpoint = requireText(endpoint, "endpoint");
        remotePath = requireText(remotePath, "remotePath");
        include = List.copyOf(Objects.requireNonNull(include, "include"));
        exclude = List.copyOf(Objects.requireNonNull(exclude, "exclude"));
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
