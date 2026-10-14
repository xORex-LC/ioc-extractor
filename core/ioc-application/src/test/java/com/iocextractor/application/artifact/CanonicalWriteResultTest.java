package com.iocextractor.application.artifact;

import com.iocextractor.application.observation.OccurrencePosition;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CanonicalWriteResultTest {

    @Test
    void mutating_write_requires_positive_revision() {
        assertThatThrownBy(() -> new CanonicalWriteResult(1, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive revision");
    }

    @Test
    void counters_must_not_be_negative() {
        assertThatThrownBy(() -> new CanonicalWriteResult(-1, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CanonicalWriteResult(0, -1, 0, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CanonicalWriteResult(0, 0, -1, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CanonicalWriteResult(0, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void ordered_fields_require_durable_observation_registration() {
        var row = new CanonicalWriteRow(
                ArtifactRow.ordered(Map.of("id", "1", "name", "latest")),
                Map.of("name", new OccurrencePosition(7)));

        assertThatThrownBy(() -> new CanonicalWriteCommand(
                "ioc_aggregate", List.of("id", "name"), List.of(row), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Ordered fields require a registered observation");
    }
}
