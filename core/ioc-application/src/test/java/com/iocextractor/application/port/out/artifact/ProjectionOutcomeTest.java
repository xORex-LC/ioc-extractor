package com.iocextractor.application.port.out.artifact;

import com.iocextractor.diagnostics.DiagnosticFactory;
import com.iocextractor.diagnostics.codes.SinkDiagnosticCodes;
import com.iocextractor.diagnostics.codes.SourceDiagnosticCodes;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProjectionOutcomeTest {

    private final DiagnosticFactory diagnostics = new DiagnosticFactory(Clock.systemUTC());

    @Test
    void copies_advisory_diagnostics() {
        var source = new ArrayList<>(java.util.List.of(
                diagnostics.create(SourceDiagnosticCodes.EMPTY_TEXT).build()));

        var outcome = new ProjectionOutcome(3, source);
        source.clear();

        assertThat(outcome.projectedRows()).isEqualTo(3);
        assertThat(outcome.diagnostics()).hasSize(1);
    }

    @Test
    void rejects_error_diagnostic_after_canonical_commit() {
        var error = diagnostics.create(SinkDiagnosticCodes.ROW_MAPPING_FAILED).build();

        assertThatThrownBy(() -> new ProjectionOutcome(0, java.util.List.of(error)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be advisory");
    }

    @Test
    void rejects_negative_projected_row_count() {
        assertThatThrownBy(() -> ProjectionOutcome.clean(-1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be negative");
    }

    @Test
    void projection_request_requires_non_blank_identity() {
        assertThatThrownBy(() -> new ArtifactProjectionRequest(" ", "masks"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("runId");
        assertThatThrownBy(() -> new ArtifactProjectionRequest("run-1", " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("artifactName");
    }
}
