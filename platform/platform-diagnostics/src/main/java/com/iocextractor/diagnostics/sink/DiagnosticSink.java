package com.iocextractor.diagnostics.sink;

import com.iocextractor.diagnostics.Diagnostic;

/**
 * Driven port for accepting diagnostics produced during processing.
 *
 * <p>Delivery is observational and must not change processing control flow. Implementations
 * connected to a pipeline runner therefore must not propagate delivery failures; potentially
 * failing delegates should be wrapped in a resilient adapter.</p>
 */
public interface DiagnosticSink {

    /**
     * Emits one diagnostic to this sink.
     *
     * @param diagnostic diagnostic to emit
     */
    void emit(Diagnostic diagnostic);
}
