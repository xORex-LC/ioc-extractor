package com.iocextractor.diagnostics.sink;

import com.iocextractor.diagnostics.Diagnostic;

import java.util.Objects;

/**
 * Diagnostic sink that intentionally discards diagnostics.
 */
public enum NoopDiagnosticSink implements DiagnosticSink {
    INSTANCE;

    @Override
    public void emit(Diagnostic diagnostic) {
        Objects.requireNonNull(diagnostic, "diagnostic");
        // Deliberately empty.
    }
}
