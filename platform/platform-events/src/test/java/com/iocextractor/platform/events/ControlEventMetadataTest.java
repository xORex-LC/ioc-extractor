package com.iocextractor.platform.events;

import org.junit.jupiter.api.Test;

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
    }

    @Test
    void rejectsBlankRequiredText() {
        assertThatThrownBy(() -> ControlEventMetadata.withoutCausation(
                " ", "slice.completed", 1, OCCURRED_AT, "corr-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("eventId");
    }

    @Test
    void rejectsNonPositiveVersion() {
        assertThatThrownBy(() -> ControlEventMetadata.withoutCausation(
                "event-1", "slice.completed", 0, OCCURRED_AT, "corr-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("eventVersion");
    }

    @Test
    void rejectsBlankCausation() {
        assertThatThrownBy(() -> new ControlEventMetadata(
                "event-1", "slice.completed", 1, OCCURRED_AT, "corr-1", " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("causationId");
    }
}
