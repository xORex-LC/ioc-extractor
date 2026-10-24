package com.iocextractor.platform.events;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable metadata supplied by a control event's producer.
 *
 * <p>Text is retained exactly as supplied, including surrounding whitespace. Required text
 * must be non-null and non-blank; causation may be null. The producer chooses all IDs and
 * the timestamp. This record does not generate IDs, enforce uniqueness, check a type registry
 * or establish compatibility between schema versions. Correlation is an application value,
 * not an automatically installed tracing context.</p>
 *
 * @param eventId non-blank event identifier; not a library-managed deduplication key
 * @param eventType non-blank service-defined event type name
 * @param eventVersion positive schema version chosen by the service
 * @param occurredAt non-null time of the fact; no comparison with the current clock is made
 * @param correlationId non-blank identifier linking related work
 * @param causationId null, or a non-blank identifier of the immediate cause
 */
public record ControlEventMetadata(String eventId,
                                   String eventType,
                                   int eventVersion,
                                   Instant occurredAt,
                                   String correlationId,
                                   String causationId) {

    /**
     * Validates the metadata without normalizing or replacing any supplied value.
     *
     * @throws IllegalArgumentException if required text is null or blank, the version is
     *         non-positive, or a non-null causation ID is blank
     * @throws NullPointerException if {@code occurredAt} is null
     */
    public ControlEventMetadata {
        eventId = requireText(eventId, "eventId");
        eventType = requireText(eventType, "eventType");
        if (eventVersion <= 0) {
            throw new IllegalArgumentException("eventVersion must be positive");
        }
        occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
        correlationId = requireText(correlationId, "correlationId");
        if (causationId != null && causationId.isBlank()) {
            throw new IllegalArgumentException("causationId must be null or non-blank");
        }
    }

    /**
     * Creates metadata with a null {@link #causationId()}.
     *
     * @param eventId non-blank event identifier
     * @param eventType non-blank service-defined event type
     * @param eventVersion positive service-defined schema version
     * @param occurredAt non-null time of the fact
     * @param correlationId non-blank identifier linking related work
     * @return validated metadata with no immediate cause
     * @throws IllegalArgumentException if required text is null or blank, or the version is non-positive
     * @throws NullPointerException if {@code occurredAt} is null
     */
    public static ControlEventMetadata withoutCausation(String eventId,
                                                        String eventType,
                                                        int eventVersion,
                                                        Instant occurredAt,
                                                        String correlationId) {
        return new ControlEventMetadata(eventId, eventType, eventVersion, occurredAt, correlationId, null);
    }

    /**
     * Returns the immediate cause as an optional instead of the nullable record accessor.
     *
     * @return the supplied causation ID, or empty when {@link #causationId()} is null
     */
    public Optional<String> causation() {
        return Optional.ofNullable(causationId);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
