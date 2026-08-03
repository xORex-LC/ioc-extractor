package com.iocextractor.application.port.out;

import com.iocextractor.diagnostics.DiagnosticException;

import java.nio.file.Path;

/**
 * Secondary (driven) port: extract plain text from a source document,
 * regardless of its format. Implementations must not leak parser-specific
 * exceptions; stopping failures are reported as a typed
 * {@link DiagnosticException}.
 */
public interface SourceReader {

    /**
     * Extracts plain text from the supplied source document.
     *
     * @param source source document path
     * @return extracted plain text
     * @throws DiagnosticException if the source cannot be read or parsed
     */
    String readText(Path source);
}
