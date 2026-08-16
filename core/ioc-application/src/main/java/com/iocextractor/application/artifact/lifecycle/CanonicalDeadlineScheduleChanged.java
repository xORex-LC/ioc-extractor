package com.iocextractor.application.artifact.lifecycle;

import com.iocextractor.platform.events.ControlEvent;
import com.iocextractor.platform.events.ControlEventMetadata;

import java.time.Instant;
import java.util.Objects;

/** Post-commit aggregate hint that nearest canonical deadline may have changed. */
public record CanonicalDeadlineScheduleChanged(ControlEventMetadata metadata,
                                               ObservationId observationId,
                                               String artifactName) implements ControlEvent {

    public static final String EVENT_TYPE = "canonical.deadline-schedule.changed";
    public static final int EVENT_VERSION = 1;

    public CanonicalDeadlineScheduleChanged {
        Objects.requireNonNull(metadata, "metadata");
        Objects.requireNonNull(observationId, "observationId");
        artifactName = requireText(artifactName, "artifactName");
    }

    /** Creates one replay-safe post-commit scheduling hint. */
    public static CanonicalDeadlineScheduleChanged from(LifecycleWriteResult result) {
        Objects.requireNonNull(result, "result");
        String observation = result.observationId().value();
        String artifact = result.artifactName();
        Instant occurredAt = result.effectiveTime().value();
        String eventId = "canonical-deadline:" + observation + ":" + artifact;
        return new CanonicalDeadlineScheduleChanged(
                ControlEventMetadata.withoutCausation(
                        eventId, EVENT_TYPE, EVENT_VERSION, occurredAt, observation),
                result.observationId(),
                artifact);
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
