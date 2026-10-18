package com.iocextractor.platform.diagnostics.sink;

import com.iocextractor.platform.diagnostics.TestCodes;
import com.iocextractor.platform.diagnostics.Diagnostic;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CollectingDiagnosticSinkTest {

    @Test
    void collecting_sink_stores_emitted_diagnostics_without_external_side_effects() {
        var diagnostic = diagnostic();
        var sink = new CollectingDiagnosticSink();

        sink.emit(diagnostic);
        var snapshot = sink.diagnostics();
        sink.emit(diagnostic);

        assertThat(snapshot).containsExactly(diagnostic);
        assertThat(sink.diagnostics()).containsExactly(diagnostic, diagnostic);
        assertThatNullPointerException().isThrownBy(() -> sink.emit(null));
        assertThat(sink.diagnostics()).containsExactly(diagnostic, diagnostic);
        assertThatThrownBy(() -> sink.diagnostics().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void noop_sink_discards_diagnostics() {
        NoopDiagnosticSink.INSTANCE.emit(diagnostic());
    }

    @Test
    void noop_sink_rejects_null_diagnostics() {
        assertThatNullPointerException()
                .isThrownBy(() -> NoopDiagnosticSink.INSTANCE.emit(null));
    }

    private Diagnostic diagnostic() {
        return Diagnostic.builder(TestCodes.EMPTY_TEXT,
                        Clock.fixed(Instant.parse("2026-06-21T00:00:00Z"), ZoneOffset.UTC))
                .with("source", "input.html")
                .build();
    }
}
