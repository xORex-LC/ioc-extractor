package com.iocextractor.diagnostics.result;

import com.iocextractor.diagnostics.Diagnostic;
import com.iocextractor.diagnostics.DiagnosticSeverity;
import com.iocextractor.diagnostics.codes.SourceDiagnosticCodes;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ResultTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-06-21T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void result_copies_diagnostics_and_reports_error_flags() {
        var diagnostic = diagnostic(DiagnosticSeverity.ERROR);
        var source = new java.util.ArrayList<>(List.of(diagnostic));

        var result = Result.of("value", source);
        source.clear();

        assertThat(result.value()).isEqualTo("value");
        assertThat(result.diagnostics()).containsExactly(diagnostic);
        assertThat(result.hasErrors()).isTrue();
        assertThat(result.hasFatal()).isFalse();
        assertThatThrownBy(() -> result.diagnostics().add(diagnostic))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void with_diagnostic_returns_a_new_result() {
        var diagnostic = diagnostic(DiagnosticSeverity.WARN);
        var result = Result.success("value").withDiagnostic(diagnostic);

        assertThat(result.value()).isEqualTo("value");
        assertThat(result.diagnostics()).containsExactly(diagnostic);
    }

    @Test
    void map_preserves_diagnostics() {
        var diagnostic = diagnostic(DiagnosticSeverity.WARN);

        var mapped = Result.of("value", List.of(diagnostic))
                .map(String::length);

        assertThat(mapped.value()).isEqualTo(5);
        assertThat(mapped.diagnostics()).containsExactly(diagnostic);
    }

    @Test
    void map_is_null_safe() {
        var called = new AtomicBoolean(false);

        var mapped = new Result<String>(null, List.of())
                .map(value -> {
                    called.set(true);
                    return value.length();
                });

        assertThat(mapped.value()).isNull();
        assertThat(called).isFalse();
    }

    @Test
    void fatal_diagnostics_are_reported() {
        var result = Result.of(null, List.of(diagnostic(DiagnosticSeverity.FATAL)));

        assertThat(result.hasErrors()).isTrue();
        assertThat(result.hasFatal()).isTrue();
    }

    private Diagnostic diagnostic(DiagnosticSeverity severity) {
        return Diagnostic.builder(SourceDiagnosticCodes.READ_FAILED, CLOCK)
                .severity(severity)
                .with("source", "input.html")
                .with("reason", "failed")
                .build();
    }
}
