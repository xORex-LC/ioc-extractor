package com.iocextractor.application.artifact.lifecycle;

import java.util.Objects;

/** Configuration translated into application-level one-way activation semantics. */
public record LifecycleActivationPolicy(boolean enabled,
                                        String policyFingerprint,
                                        ExistingRecordsActivationPolicy existingRecords) {

    public LifecycleActivationPolicy {
        Objects.requireNonNull(policyFingerprint, "policyFingerprint");
        Objects.requireNonNull(existingRecords, "existingRecords");
        if (policyFingerprint.isBlank()) {
            throw new IllegalArgumentException("Lifecycle policy fingerprint must not be blank");
        }
    }

    public static LifecycleActivationPolicy disabled() {
        return new LifecycleActivationPolicy(
                false, "record-validity:disabled:v1", ExistingRecordsActivationPolicy.REJECT);
    }
}
