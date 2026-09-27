package com.iocextractor.adapter.processing.camel.contract;

import java.util.Objects;

/** Stable technical identity of an expected operation failure within one invocation. */
public record FailureReference(String producerId, String reasonCode) {
    public FailureReference {
        Objects.requireNonNull(producerId);
        Objects.requireNonNull(reasonCode);
    }
}
