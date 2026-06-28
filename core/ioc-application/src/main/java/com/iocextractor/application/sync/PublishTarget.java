package com.iocextractor.application.sync;

import java.util.Objects;

/**
 * Configured remote delivery target for one export profile.
 */
public record PublishTarget(String targetId,
                            String endpoint,
                            String remotePath,
                            String exportProfile) {

    public PublishTarget {
        targetId = requireText(targetId, "targetId");
        endpoint = requireText(endpoint, "endpoint");
        remotePath = requireText(remotePath, "remotePath");
        exportProfile = requireText(exportProfile, "exportProfile");
    }

    /** Returns the final remote slice path below this target root. */
    public String sliceRemotePath(String sliceName) {
        String safeSliceName = safeSegment(sliceName, "sliceName");
        String normalized = remotePath.endsWith("/") ? remotePath.substring(0, remotePath.length() - 1) : remotePath;
        return normalized + "/" + safeSliceName;
    }

    private static String safeSegment(String value, String field) {
        String segment = requireText(value, field);
        if (segment.contains("/") || segment.contains("\\") || segment.equals(".") || segment.equals("..")) {
            throw new IllegalArgumentException(field + " must be one safe path segment");
        }
        return segment;
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
