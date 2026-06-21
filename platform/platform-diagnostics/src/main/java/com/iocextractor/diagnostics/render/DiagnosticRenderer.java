package com.iocextractor.diagnostics.render;

import com.iocextractor.diagnostics.Diagnostic;

/**
 * Port that renders diagnostic data into a human-readable message.
 */
public interface DiagnosticRenderer {

    /**
     * Renders one diagnostic.
     *
     * @param diagnostic diagnostic to render
     * @return rendered message
     */
    String render(Diagnostic diagnostic);
}
