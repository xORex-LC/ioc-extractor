package com.iocextractor.application.artifact;

import com.iocextractor.platform.events.ControlEvent;
import com.iocextractor.platform.events.ControlEventMetadata;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Control-plane fact emitted after any operation durably changes canonical artifacts. */
public record CanonicalArtifactsChanged(ControlEventMetadata metadata,
                                        String operationId,
                                        List<String> affectedArtifacts) implements ControlEvent {

    public static final String EVENT_TYPE = "artifact.canonical-artifacts.changed";
    public static final int EVENT_VERSION = 1;

    public CanonicalArtifactsChanged {
        metadata = Objects.requireNonNull(metadata, "metadata");
        operationId = requireText(operationId, "operationId");
        affectedArtifacts = requireArtifactNames(affectedArtifacts);
    }

    /** Creates the fact from the completed operation identity and affected artifacts. */
    public static CanonicalArtifactsChanged from(String operationId,
                                                 List<String> affectedArtifacts,
                                                 Instant occurredAt) {
        String requiredOperationId = requireText(operationId, "operationId");
        ControlEventMetadata metadata = ControlEventMetadata.withoutCausation(
                "canonical-artifacts-changed:" + requiredOperationId,
                EVENT_TYPE,
                EVENT_VERSION,
                Objects.requireNonNull(occurredAt, "occurredAt"),
                requiredOperationId);
        return new CanonicalArtifactsChanged(metadata, requiredOperationId, affectedArtifacts);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    private static List<String> requireArtifactNames(List<String> values) {
        Objects.requireNonNull(values, "affectedArtifacts");
        if (values.isEmpty()) {
            throw new IllegalArgumentException("affectedArtifacts must not be empty");
        }
        List<String> artifactNames = new ArrayList<>(values.size());
        for (String value : values) {
            artifactNames.add(requireText(value, "artifactName"));
        }
        return List.copyOf(artifactNames);
    }
}
