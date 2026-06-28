package com.iocextractor.application.port.in.sync;

/** Inbound port for publishing completed export slices to configured remote targets. */
public interface ArtifactPublishUseCase {

    ArtifactPublishResult publish(ArtifactPublishCommand command);
}
