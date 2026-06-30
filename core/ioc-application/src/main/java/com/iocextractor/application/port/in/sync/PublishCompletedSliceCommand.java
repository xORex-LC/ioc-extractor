package com.iocextractor.application.port.in.sync;

import java.util.Optional;

/** Command to publish one known completed export slice to selected configured targets. */
public record PublishCompletedSliceCommand(String profile,
                                           String sliceId,
                                           String sliceName,
                                           Optional<String> target,
                                           Optional<String> endpoint,
                                           String correlationId,
                                           String causationId) {

    public PublishCompletedSliceCommand {
        profile = requireText(profile, "profile");
        sliceId = requireText(sliceId, "sliceId");
        sliceName = requireText(sliceName, "sliceName");
        target = target == null ? Optional.empty() : target;
        endpoint = endpoint == null ? Optional.empty() : endpoint;
        target.ifPresent(value -> requireText(value, "target"));
        endpoint.ifPresent(value -> requireText(value, "endpoint"));
        correlationId = requireText(correlationId, "correlationId");
        if (causationId != null && causationId.isBlank()) {
            throw new IllegalArgumentException("causationId must be null or non-blank");
        }
    }

    public PublishCompletedSliceCommand(String profile,
                                        String sliceId,
                                        String sliceName,
                                        String target,
                                        String endpoint,
                                        String correlationId,
                                        String causationId) {
        this(profile, sliceId, sliceName, Optional.ofNullable(target), Optional.ofNullable(endpoint),
                correlationId, causationId);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
