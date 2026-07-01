package com.iocextractor.application.sync;

import com.iocextractor.platform.events.ControlEvent;
import com.iocextractor.platform.events.ControlEventMetadata;

import java.util.List;
import java.util.Objects;

/** Control-plane fact emitted when remote source monitoring detects fetchable object identities. */
public record RemoteChangeBatchDetected(ControlEventMetadata metadata,
                                        String sourceId,
                                        String endpoint,
                                        String remotePath,
                                        List<RemoteObject> objects) implements ControlEvent {

    public static final String EVENT_TYPE = "sync.remote.change_batch_detected";
    public static final int EVENT_VERSION = 1;

    public RemoteChangeBatchDetected {
        metadata = Objects.requireNonNull(metadata, "metadata");
        sourceId = requireText(sourceId, "sourceId");
        endpoint = requireText(endpoint, "endpoint");
        remotePath = requireText(remotePath, "remotePath");
        objects = List.copyOf(Objects.requireNonNull(objects, "objects"));
        if (objects.isEmpty()) {
            throw new IllegalArgumentException("objects must not be empty");
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
