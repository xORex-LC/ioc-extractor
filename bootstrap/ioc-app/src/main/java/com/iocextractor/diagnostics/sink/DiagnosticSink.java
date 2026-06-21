package com.iocextractor.diagnostics.sink;

import com.iocextractor.diagnostics.Diagnostic;

/**
 * Driven port for accepting diagnostics produced during processing.
 */
public interface DiagnosticSink {

    /**
     * Emits one diagnostic to this sink.
     *
     * @param diagnostic diagnostic to emit
     */
    void emit(Diagnostic diagnostic);
}
