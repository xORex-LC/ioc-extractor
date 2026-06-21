package com.iocextractor.diagnostics;

import com.iocextractor.diagnostics.codes.SourceDiagnosticCodes;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DiagnosticTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-06-21T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void builder_uses_default_severity_and_clock_timestamp() {
        var diagnostic = Diagnostic.builder(SourceDiagnosticCodes.READ_FAILED, FIXED_CLOCK)
                .with("source", "input.html")
                .with("reason", "denied")
                .build();

        assertThat(diagnostic.code()).isEqualTo(SourceDiagnosticCodes.READ_FAILED);
        assertThat(diagnostic.category()).isEqualTo(DiagnosticCategory.SOURCE);
        assertThat(diagnostic.severity()).isEqualTo(DiagnosticSeverity.FATAL);
        assertThat(diagnostic.timestamp()).isEqualTo(Instant.parse("2026-06-21T00:00:00Z"));
        assertThat(diagnostic.context()).containsEntry("source", "input.html");
    }

    @Test
    void factory_centralizes_the_clock() {
        var factory = new DiagnosticFactory(FIXED_CLOCK);

        var diagnostic = factory.create(SourceDiagnosticCodes.EMPTY_TEXT)
                .with("source", "empty.html")
                .build();

        assertThat(diagnostic.timestamp()).isEqualTo(FIXED_CLOCK.instant());
    }

    @Test
    void context_is_copied_and_immutable() {
        var sourceContext = new java.util.LinkedHashMap<String, Object>();
        sourceContext.put("source", "input.html");

        var diagnostic = Diagnostic.builder(SourceDiagnosticCodes.EMPTY_TEXT, FIXED_CLOCK)
                .context(sourceContext)
                .build();
        sourceContext.put("source", "mutated.html");

        assertThat(diagnostic.context()).containsEntry("source", "input.html");
        assertThatThrownBy(() -> diagnostic.context().put("new", "value"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void equality_uses_code_and_context_only() {
        var first = Diagnostic.builder(SourceDiagnosticCodes.READ_FAILED, FIXED_CLOCK)
                .with("source", "input.html")
                .with("reason", "denied")
                .cause(new IllegalStateException("first"))
                .build();
        var second = Diagnostic.builder(SourceDiagnosticCodes.READ_FAILED,
                        Clock.fixed(Instant.parse("2026-06-22T00:00:00Z"), ZoneOffset.UTC))
                .severity(DiagnosticSeverity.ERROR)
                .with("source", "input.html")
                .with("reason", "denied")
                .cause(new IllegalArgumentException("second"))
                .build();

        assertThat(second).isEqualTo(first);
        assertThat(second).hasSameHashCodeAs(first);
    }

    @Test
    void cause_is_optional() {
        var cause = new IllegalStateException("failed");

        var diagnostic = Diagnostic.builder(SourceDiagnosticCodes.READ_FAILED, FIXED_CLOCK)
                .with("source", "input.html")
                .with("reason", "denied")
                .cause(cause)
                .build();

        assertThat(diagnostic.cause()).contains(cause);
    }

    @Test
    void builder_rejects_nulls() {
        assertThatNullPointerException()
                .isThrownBy(() -> Diagnostic.builder(null, FIXED_CLOCK));
        assertThatNullPointerException()
                .isThrownBy(() -> Diagnostic.builder(SourceDiagnosticCodes.READ_FAILED, null));
        assertThatNullPointerException()
                .isThrownBy(() -> Diagnostic.builder(SourceDiagnosticCodes.READ_FAILED, FIXED_CLOCK)
                        .with(null, "value"));
        assertThatNullPointerException()
                .isThrownBy(() -> Diagnostic.builder(SourceDiagnosticCodes.READ_FAILED, FIXED_CLOCK)
                        .with("key", null));
        assertThatNullPointerException()
                .isThrownBy(() -> {
                    var values = new HashMap<String, Object>();
                    values.put("key", "value");
                    values.put("other", null);
                    Diagnostic.builder(SourceDiagnosticCodes.READ_FAILED, FIXED_CLOCK).context(values);
                });
    }
}
