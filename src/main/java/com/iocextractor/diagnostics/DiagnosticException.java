package com.iocextractor.diagnostics;

import com.iocextractor.common.IocExtractorException;

import java.util.Objects;

/**
 * Unchecked exception carrying a fatal diagnostic across an orchestration
 * boundary.
 */
public class DiagnosticException extends IocExtractorException {

    private final Diagnostic diagnostic;

    /**
     * Creates an exception for the supplied diagnostic.
     *
     * @param diagnostic fatal diagnostic
     */
    public DiagnosticException(Diagnostic diagnostic) {
        super(diagnostic.code().id(), diagnostic.cause().orElse(null));
        this.diagnostic = Objects.requireNonNull(diagnostic, "diagnostic");
    }

    /**
     * Returns the diagnostic carried by this exception.
     *
     * @return diagnostic
     */
    public Diagnostic diagnostic() {
        return diagnostic;
    }
}
