package com.iocextractor.application.port.in.sync;

import com.iocextractor.application.sync.RemoteObject;

import java.util.List;
import java.util.Objects;

/** Command for executing fetch of already detected remote object identities. */
public record FetchRemoteObjectsCommand(String source,
                                        String endpoint,
                                        String remotePath,
                                        List<RemoteObject> objects,
                                        boolean dryRun) {

    public FetchRemoteObjectsCommand {
        source = requireText(source, "source");
        endpoint = requireText(endpoint, "endpoint");
        remotePath = requireText(remotePath, "remotePath");
        objects = List.copyOf(Objects.requireNonNull(objects, "objects"));
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
