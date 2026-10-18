package com.iocextractor.platform.diagnostics.sink;

import com.iocextractor.platform.diagnostics.Diagnostic;

import java.util.Objects;

/**
 * Diagnostic sink that intentionally discards diagnostics.
 */
public enum NoopDiagnosticSink implements DiagnosticSink {
    /** Shared stateless sink. */
    INSTANCE;

    /** {@inheritDoc} */
    @Override
    public void emit(Diagnostic diagnostic) {
        Objects.requireNonNull(diagnostic, "diagnostic");
    }
}
