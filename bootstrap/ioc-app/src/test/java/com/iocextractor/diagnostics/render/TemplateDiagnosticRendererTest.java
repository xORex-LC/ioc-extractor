package com.iocextractor.diagnostics.render;

import com.iocextractor.diagnostics.Diagnostic;
import com.iocextractor.diagnostics.codes.SourceDiagnosticCodes;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class TemplateDiagnosticRendererTest {

    private final TemplateDiagnosticRenderer renderer = new TemplateDiagnosticRenderer();

    @Test
    void renders_default_template_with_context_values() {
        var diagnostic = Diagnostic.builder(SourceDiagnosticCodes.READ_FAILED,
                        Clock.fixed(Instant.parse("2026-06-21T00:00:00Z"), ZoneOffset.UTC))
                .with("source", "input.html")
                .with("reason", "denied")
                .build();

        assertThat(renderer.render(diagnostic))
                .isEqualTo("Source input.html could not be read: denied");
    }

    @Test
    void keeps_missing_placeholders_visible() {
        var diagnostic = Diagnostic.builder(SourceDiagnosticCodes.READ_FAILED,
                        Clock.fixed(Instant.parse("2026-06-21T00:00:00Z"), ZoneOffset.UTC))
                .with("source", "input.html")
                .build();

        assertThat(renderer.render(diagnostic))
                .isEqualTo("Source input.html could not be read: {reason}");
    }

    @Test
    void does_not_reinterpret_placeholder_like_context_values() {
        var diagnostic = Diagnostic.builder(SourceDiagnosticCodes.READ_FAILED,
                        Clock.fixed(Instant.parse("2026-06-21T00:00:00Z"), ZoneOffset.UTC))
                .with("source", "{reason}")
                .with("reason", "denied")
                .build();

        assertThat(renderer.render(diagnostic))
                .isEqualTo("Source {reason} could not be read: denied");
    }
}
