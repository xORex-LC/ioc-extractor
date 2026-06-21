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
        super(message(diagnostic), diagnostic.cause().orElse(null));
        this.diagnostic = Objects.requireNonNull(diagnostic, "diagnostic");
    }

    private static String message(Diagnostic diagnostic) {
        Objects.requireNonNull(diagnostic, "diagnostic");
        return diagnostic.context().isEmpty()
                ? diagnostic.code().id()
                : diagnostic.code().id() + " " + diagnostic.context();
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
