package com.iocextractor.application.ingest;

import com.iocextractor.platform.events.ControlEvent;
import com.iocextractor.platform.events.ControlEventMetadata;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Control-plane fact emitted after an ingest run reaches durable completed state. */
public record CanonicalArtifactsChanged(ControlEventMetadata metadata,
                                        String runId,
                                        List<String> artifactNames) implements ControlEvent {

    public static final String EVENT_TYPE = "ingest.canonical-artifacts.changed";
    public static final int EVENT_VERSION = 1;

    public CanonicalArtifactsChanged {
        metadata = Objects.requireNonNull(metadata, "metadata");
        runId = requireText(runId, "runId");
        artifactNames = requireArtifactNames(artifactNames);
    }

    /** Creates the event from the completed ingest run identity and affected artifacts. */
    public static CanonicalArtifactsChanged from(String runId, List<String> artifactNames, Instant occurredAt) {
        String requiredRunId = requireText(runId, "runId");
        ControlEventMetadata metadata = ControlEventMetadata.withoutCausation(
                "canonical-artifacts-changed:" + requiredRunId,
                EVENT_TYPE,
                EVENT_VERSION,
                Objects.requireNonNull(occurredAt, "occurredAt"),
                requiredRunId);
        return new CanonicalArtifactsChanged(metadata, requiredRunId, artifactNames);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    private static List<String> requireArtifactNames(List<String> values) {
        Objects.requireNonNull(values, "artifactNames");
        if (values.isEmpty()) {
            throw new IllegalArgumentException("artifactNames must not be empty");
        }
        List<String> artifactNames = new ArrayList<>(values.size());
        for (String value : values) {
            artifactNames.add(requireText(value, "artifactName"));
        }
        return List.copyOf(artifactNames);
    }
}
