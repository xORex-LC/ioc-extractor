package com.iocextractor.platform.events;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ControlEventMetadataTest {

    private static final Instant OCCURRED_AT = Instant.parse("2026-06-30T12:00:00Z");

    @Test
    void storesRequiredMetadataAndOptionalCausation() {
        ControlEventMetadata metadata = new ControlEventMetadata(
                "event-1", "slice.completed", 1, OCCURRED_AT, "corr-1", "cause-1");

        assertThat(metadata.eventId()).isEqualTo("event-1");
        assertThat(metadata.eventType()).isEqualTo("slice.completed");
        assertThat(metadata.eventVersion()).isEqualTo(1);
        assertThat(metadata.occurredAt()).isEqualTo(OCCURRED_AT);
        assertThat(metadata.correlationId()).isEqualTo("corr-1");
        assertThat(metadata.causation()).contains("cause-1");
    }

    @Test
    void createsMetadataWithoutCausation() {
        ControlEventMetadata metadata = ControlEventMetadata.withoutCausation(
                "event-1", "slice.completed", 1, OCCURRED_AT, "corr-1");

        assertThat(metadata.causation()).isEmpty();
        assertThat(metadata.causationId()).isNull();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t\n", "\u2003"})
    void rejectsMissingEventId(String eventId) {
        assertThatThrownBy(() -> ControlEventMetadata.withoutCausation(
                eventId, "slice.completed", 1, OCCURRED_AT, "corr-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("eventId");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t\n", "\u2003"})
    void rejectsMissingEventType(String eventType) {
        assertThatThrownBy(() -> ControlEventMetadata.withoutCausation(
                "event-1", eventType, 1, OCCURRED_AT, "corr-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("eventType");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t\n", "\u2003"})
    void rejectsMissingCorrelationId(String correlationId) {
        assertThatThrownBy(() -> ControlEventMetadata.withoutCausation(
                "event-1", "slice.completed", 1, OCCURRED_AT, correlationId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("correlationId");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, Integer.MIN_VALUE})
    void rejectsNonPositiveVersion(int version) {
        assertThatThrownBy(() -> ControlEventMetadata.withoutCausation(
                "event-1", "slice.completed", version, OCCURRED_AT, "corr-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("eventVersion");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "\t\n", "\u2003"})
    void rejectsBlankCausation(String causationId) {
        assertThatThrownBy(() -> new ControlEventMetadata(
                "event-1", "slice.completed", 1, OCCURRED_AT, "corr-1", causationId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("causationId");
    }

    @Test
    void rejectsMissingOccurrenceTime() {
        assertThatThrownBy(() -> ControlEventMetadata.withoutCausation(
                "event-1", "slice.completed", 1, null, "corr-1"))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("occurredAt");
    }

    @Test
    void retainsTextAndAcceptsServiceChosenVersionAndFutureTime() {
        Instant future = Instant.parse("2100-01-01T00:00:00Z");

        ControlEventMetadata metadata = new ControlEventMetadata(
                " event-1 ", " custom event ", Integer.MAX_VALUE, future, " corr-1 ", " cause-1 ");

        assertThat(metadata.eventId()).isEqualTo(" event-1 ");
        assertThat(metadata.eventType()).isEqualTo(" custom event ");
        assertThat(metadata.eventVersion()).isEqualTo(Integer.MAX_VALUE);
        assertThat(metadata.occurredAt()).isEqualTo(future);
        assertThat(metadata.correlationId()).isEqualTo(" corr-1 ");
        assertThat(metadata.causation()).contains(" cause-1 ");
    }
}
