package com.iocextractor.diagnostics.render;

import com.iocextractor.diagnostics.Diagnostic;

/** Strategy for formatting one diagnostic context value during rendering. */
@FunctionalInterface
public interface DiagnosticContextFormatter {

    /** Formats one placeholder value without mutating the diagnostic. */
    String format(Diagnostic diagnostic, String key, Object value);
}
