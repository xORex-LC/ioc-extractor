package com.iocextractor.application.port.in.sync;

/** Inbound port for publishing completed export slices to configured remote targets. */
public interface ArtifactPublishUseCase {

    /** Discovers verified slices and materializes missing ledger pairs without remote I/O. */
    ArtifactPublishResult reconcile(ArtifactPublishCommand command);

    /** Attempts all selected retryable publish pairs without profile discovery. */
    ArtifactPublishExecutionResult publish(ArtifactPublishCommand command);

    /** Attempts publish for one already known completed slice without scanning the profile. */
    ArtifactPublishExecutionResult publishCompletedSlice(PublishCompletedSliceCommand command);
}
