package com.iocextractor.platform.events;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable metadata envelope shared by all control events.
 *
 * @param eventId stable event identifier
 * @param eventType stable event type name
 * @param eventVersion positive schema version for the event type
 * @param occurredAt timestamp when the fact occurred
 * @param correlationId trace/correlation identifier propagated across related work
 * @param causationId optional identifier of the event or command that caused this event
 */
public record ControlEventMetadata(String eventId,
                                   String eventType,
                                   int eventVersion,
                                   Instant occurredAt,
                                   String correlationId,
                                   String causationId) {

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

    /** Creates metadata without a causation id. */
    public static ControlEventMetadata withoutCausation(String eventId,
                                                        String eventType,
                                                        int eventVersion,
                                                        Instant occurredAt,
                                                        String correlationId) {
        return new ControlEventMetadata(eventId, eventType, eventVersion, occurredAt, correlationId, null);
    }

    /** Returns the optional causation id without exposing null to callers. */
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
