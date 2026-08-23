package com.iocextractor.application.artifact.lifecycle;

import java.util.Objects;

/** One committed, resumable legacy-expiration batch. */
public record LifecycleActivationBatchResult(String artifactName,
                                             int expired,
                                             boolean moreLegacyRows) {

    public LifecycleActivationBatchResult {
        Objects.requireNonNull(artifactName, "artifactName");
        if (artifactName.isBlank() || expired < 0) {
            throw new IllegalArgumentException("Activation batch facts are invalid");
        }
    }
}
