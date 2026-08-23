package com.iocextractor.application.artifact;

import java.util.List;
import java.util.Objects;

/** Correlated set of usable aliases resolved together at one active snapshot. */
public record CanonicalMatchRequest(String requestId, List<CanonicalKeyMaterial> keys) {

    public CanonicalMatchRequest {
        if (requestId == null || requestId.isBlank()) {
            throw new IllegalArgumentException("Canonical match request id must not be blank");
        }
        keys = List.copyOf(Objects.requireNonNull(keys, "keys"));
    }
}
