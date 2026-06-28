package com.iocextractor.application.port.in.sync;

/** IO-free preflight for CLI sync filters and feature availability. */
public interface ValidateSyncSelectionUseCase {

    /** Validates fetch enablement and optional source/endpoint selection. */
    void validateFetch(RemoteFetchCommand command);

    /** Validates publish enablement and optional profile/target/endpoint selection. */
    void validatePublish(ArtifactPublishCommand command);
}
