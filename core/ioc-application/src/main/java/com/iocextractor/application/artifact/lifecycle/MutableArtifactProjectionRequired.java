package com.iocextractor.application.artifact.lifecycle;

import com.iocextractor.platform.events.ControlEvent;
import com.iocextractor.platform.events.ControlEventMetadata;

import java.time.Instant;
import java.util.Objects;

/** Post-commit aggregate hint for already durable mutable-projection work. */
public record MutableArtifactProjectionRequired(ControlEventMetadata metadata,
                                                String workId,
                                                String artifactName,
                                                ProjectionGeneration requiredGeneration) implements ControlEvent {

    public static final String EVENT_TYPE = "canonical.mutable-projection.required";
    public static final int EVENT_VERSION = 1;

    public MutableArtifactProjectionRequired {
        Objects.requireNonNull(metadata, "metadata");
        workId = requireText(workId, "workId");
        artifactName = requireText(artifactName, "artifactName");
        Objects.requireNonNull(requiredGeneration, "requiredGeneration");
    }

    /** Creates an aggregate hint for one artifact and durable work generation. */
    public static MutableArtifactProjectionRequired from(String workId,
                                                         String artifactName,
                                                         ProjectionGeneration generation,
                                                         Instant occurredAt) {
        String requiredWorkId = requireText(workId, "workId");
        String requiredArtifact = requireText(artifactName, "artifactName");
        Objects.requireNonNull(generation, "generation");
        String eventId = "mutable-projection:" + requiredWorkId + ":"
                + requiredArtifact + ":" + generation.value();
        return new MutableArtifactProjectionRequired(
                ControlEventMetadata.withoutCausation(
                        eventId, EVENT_TYPE, EVENT_VERSION,
                        Objects.requireNonNull(occurredAt, "occurredAt"), requiredWorkId),
                requiredWorkId,
                requiredArtifact,
                generation);
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
