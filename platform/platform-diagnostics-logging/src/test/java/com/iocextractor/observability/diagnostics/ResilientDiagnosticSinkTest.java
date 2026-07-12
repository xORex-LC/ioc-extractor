package com.iocextractor.observability.diagnostics;

import ch.qos.logback.classic.Logger;
import com.iocextractor.diagnostics.Diagnostic;
import com.iocextractor.diagnostics.codes.PipelineDiagnosticCodes;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThatNoException;

class ResilientDiagnosticSinkTest {

    @Test
    void isolates_delegate_failure_from_processing() {
        var fallback = (Logger) LoggerFactory.getLogger("test.resilient-diagnostic-sink");
        fallback.detachAndStopAllAppenders();
        fallback.setAdditive(false);
        var sink = new ResilientDiagnosticSink(
                ignored -> { throw new IllegalStateException("delegate failed"); },
                fallback);
        Diagnostic diagnostic = Diagnostic.builder(PipelineDiagnosticCodes.STAGE_FAILED, Clock.systemUTC())
                .with("stage", "test")
                .with("reason", "failed")
                .build();

        assertThatNoException().isThrownBy(() -> sink.emit(diagnostic));
    }
}
