package com.iocextractor.application.ingest;

import com.iocextractor.application.artifact.lifecycle.ConfirmationReceiptContext;
import com.iocextractor.application.artifact.lifecycle.ConfirmationReceiptId;
import com.iocextractor.application.artifact.lifecycle.EffectiveTime;
import com.iocextractor.application.artifact.lifecycle.LifecycleTimeSource;
import com.iocextractor.application.artifact.lifecycle.LifecycleWriteContext;
import com.iocextractor.application.artifact.lifecycle.ObservationId;
import com.iocextractor.application.port.in.artifact.lifecycle.ReplayConfirmationReceiptUseCase;
import com.iocextractor.application.port.out.artifact.lifecycle.CanonicalObservationStore;

import java.time.Duration;
import java.util.Objects;

/** Fixed-validity collaborators kept behind one optional ingestion capability. */
public record IngestionLifecycleSupport(ReplayConfirmationReceiptUseCase receiptReplay,
                                       CanonicalObservationStore observations,
                                       LifecycleTimeSource timeSource,
                                       String processingPolicyFingerprint,
                                       Duration retention) {

    public IngestionLifecycleSupport {
        Objects.requireNonNull(receiptReplay, "receiptReplay");
        Objects.requireNonNull(observations, "observations");
        Objects.requireNonNull(timeSource, "timeSource");
        Objects.requireNonNull(processingPolicyFingerprint, "processingPolicyFingerprint");
        Objects.requireNonNull(retention, "retention");
        if (processingPolicyFingerprint.isBlank() || retention.isZero() || retention.isNegative()) {
            throw new IllegalArgumentException("Ingestion lifecycle support requires positive bounded facts");
        }
    }

    public LifecycleWriteContext context(SourceUnit unit, int expectedArtifacts) {
        return new LifecycleWriteContext(
                unit.observationId(),
                unit.key().value(),
                new ConfirmationReceiptContext(
                        new ConfirmationReceiptId("receipt:" + unit.observationId().value()),
                        processingPolicyFingerprint,
                        expectedArtifacts,
                        retention));
    }

    public void markTerminal(SourceUnit unit) {
        markTerminal(unit.observationId());
    }

    public void markTerminal(ObservationId observationId) {
        observations.markTerminal(observationId, EffectiveTime.at(timeSource.now().value()), retention);
    }
}
