package com.iocextractor.application.port.in.sync;

/** Inbound port for publishing completed export slices to configured remote targets. */
public interface ArtifactPublishUseCase {

    /** Discovers verified slices and materializes missing ledger pairs without remote I/O. */
    ArtifactPublishResult reconcile(ArtifactPublishCommand command);

    /** Reconciles and attempts all selected retryable publish pairs. */
    ArtifactPublishResult publish(ArtifactPublishCommand command);

    /** Attempts publish for one already known completed slice without scanning the profile. */
    ArtifactPublishResult publishCompletedSlice(PublishCompletedSliceCommand command);
}
