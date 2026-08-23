package com.iocextractor.application.sync;

import java.util.Objects;

/** Transport-neutral identity and location required to watch one remote directory. */
public record RemoteWatchTarget(String sourceId, String endpoint, String remotePath) {

    public RemoteWatchTarget {
        sourceId = requireText(sourceId, "sourceId");
        endpoint = requireText(endpoint, "endpoint");
        remotePath = requireText(remotePath, "remotePath");
    }

    /** Creates the narrow watch view of a configured fetch source. */
    public static RemoteWatchTarget from(RemoteFetchSource source) {
        Objects.requireNonNull(source, "source");
        return new RemoteWatchTarget(source.sourceId(), source.endpoint(), source.remotePath());
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
