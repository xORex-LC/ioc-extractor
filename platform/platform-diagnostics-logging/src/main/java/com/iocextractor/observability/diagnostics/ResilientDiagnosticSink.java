package com.iocextractor.observability.diagnostics;

import com.iocextractor.diagnostics.Diagnostic;
import com.iocextractor.diagnostics.sink.DiagnosticSink;
import org.slf4j.Logger;

import java.util.Objects;

/**
 * Non-throwing decorator that prevents observational delivery failures from
 * changing the processing outcome.
 *
 * <p>The fallback logger is deliberately outside the diagnostic path, so a
 * failed delegate cannot trigger recursive diagnostic emission.
 */
public final class ResilientDiagnosticSink implements DiagnosticSink {

    private final DiagnosticSink delegate;
    private final Logger fallbackLogger;

    public ResilientDiagnosticSink(DiagnosticSink delegate, Logger fallbackLogger) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.fallbackLogger = Objects.requireNonNull(fallbackLogger, "fallbackLogger");
    }

    @Override
    public void emit(Diagnostic diagnostic) {
        Objects.requireNonNull(diagnostic, "diagnostic");
        try {
            delegate.emit(diagnostic);
        } catch (RuntimeException deliveryFailure) {
            reportFailure(diagnostic, deliveryFailure);
        }
    }

    private void reportFailure(Diagnostic diagnostic, RuntimeException deliveryFailure) {
        try {
            fallbackLogger.error("diagnostic delivery failed for {}", diagnostic.code().id(), deliveryFailure);
        } catch (RuntimeException ignored) {
            // Observational delivery is best-effort and must never recurse or alter processing.
        }
    }
}
