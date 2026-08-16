package com.iocextractor.application.artifact.lifecycle;

import com.iocextractor.application.port.out.artifact.lifecycle.CanonicalArtifactWriter;
import com.iocextractor.platform.events.ControlEvent;
import com.iocextractor.platform.events.ControlEventPublisher;

import java.util.Objects;

/** Adds lossy post-commit deadline/projection hints around the canonical writer port. */
public final class EventPublishingCanonicalArtifactWriter implements CanonicalArtifactWriter {

    private final CanonicalArtifactWriter delegate;
    private final ControlEventPublisher events;

    public EventPublishingCanonicalArtifactWriter(CanonicalArtifactWriter delegate,
                                                  ControlEventPublisher events) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.events = Objects.requireNonNull(events, "events");
    }

    @Override
    public LifecycleWriteResult confirm(CanonicalArtifactConfirmation confirmation) {
        LifecycleWriteResult result = delegate.confirm(confirmation);
        if (result.replayed() || result.confirmedRecords() == 0) {
            return result;
        }
        publish(CanonicalDeadlineScheduleChanged.from(result));
        if (result.publicRowsInserted() > 0) {
            publish(MutableArtifactProjectionRequired.from(
                    "observation-" + result.observationId().value(),
                    result.artifactName(),
                    result.requiredProjectionGeneration(),
                    result.effectiveTime().value()));
        }
        return result;
    }

    private void publish(ControlEvent event) {
        try {
            events.publish(event);
        } catch (RuntimeException ignored) {
            // Durable deadline and projection state remain the correctness authority.
        }
    }
}
