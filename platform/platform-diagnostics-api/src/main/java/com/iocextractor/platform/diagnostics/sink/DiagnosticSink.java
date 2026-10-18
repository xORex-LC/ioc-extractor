package com.iocextractor.platform.diagnostics.sink;

import com.iocextractor.platform.diagnostics.Diagnostic;

/**
 * Driven port for accepting diagnostics produced during processing.
 *
 * <p>Delivery is observational and must not change processing control flow. Implementations
 * connected to a pipeline runner therefore must not propagate delivery failures; potentially
 * failing delegates should be wrapped in {@link ResilientDiagnosticSink}.</p>
 * <p>Calls are synchronous. The port promises no queue, persistence, retry or
 * thread safety. Producers own ordering; implementations own their resources.
 */
public interface DiagnosticSink {

    /**
     * Emits one diagnostic to this sink.
     *
     * @param diagnostic non-null occurrence to emit, without producer-side deduplication
     * @throws NullPointerException if diagnostic is null
     */
    void emit(Diagnostic diagnostic);
}
