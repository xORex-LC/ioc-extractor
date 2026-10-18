package com.iocextractor.platform.diagnostics.sink;

import com.iocextractor.platform.diagnostics.Diagnostic;
import com.iocextractor.platform.diagnostics.TestCodes;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

class ResilientDiagnosticSinkTest {
    private final Diagnostic diagnostic = Diagnostic.builder(TestCodes.STAGE_FAILED, Clock.systemUTC()).build();

    @Test
    void reports_the_original_delivery_failure_once() {
        var failure = new IllegalStateException("delegate failed");
        var reported = new ArrayList<Object>();
        var sink = new ResilientDiagnosticSink(ignored -> { throw failure; }, (value, cause) -> {
            reported.add(value);
            reported.add(cause);
        });
        sink.emit(diagnostic);
        assertThat(reported).containsExactly(diagnostic, failure);
    }

    @Test
    void contains_fallback_runtime_failure_without_recursion() {
        var sink = new ResilientDiagnosticSink(ignored -> { throw new IllegalStateException("delegate"); },
                (value, cause) -> { throw new IllegalStateException("fallback"); });
        assertThatNoException().isThrownBy(() -> sink.emit(diagnostic));
    }

    @Test
    void successful_delivery_does_not_call_the_fallback() {
        var collected = new CollectingDiagnosticSink();
        var sink = new ResilientDiagnosticSink(collected, (value, cause) -> fail("unexpected fallback"));
        sink.emit(diagnostic);
        assertThat(collected.diagnostics()).containsExactly(diagnostic);
    }

    @Test
    void rejects_null_before_either_callback() {
        var sink = new ResilientDiagnosticSink(value -> fail("unexpected delegate"),
                (value, cause) -> fail("unexpected fallback"));
        assertThatNullPointerException().isThrownBy(() -> sink.emit(null));
        assertThatNullPointerException().isThrownBy(() -> new ResilientDiagnosticSink(null, (v, e) -> { }));
        assertThatNullPointerException().isThrownBy(() -> new ResilientDiagnosticSink(value -> { }, null));
    }

    @Test
    void propagates_errors_from_delegate_and_fallback_unchanged() {
        var error = new AssertionError("unrecoverable");
        var delegateError = new ResilientDiagnosticSink(value -> { throw error; },
                (value, cause) -> fail("unexpected fallback"));
        var fallbackError = new ResilientDiagnosticSink(value -> { throw new IllegalStateException(); },
                (value, cause) -> { throw error; });
        for (var sink : List.of(delegateError, fallbackError)) {
            assertThatThrownBy(() -> sink.emit(diagnostic)).isSameAs(error);
        }
    }
}
