package com.iocextractor.diagnostics.catalog;

import com.iocextractor.diagnostics.DiagnosticCode;

import java.util.List;

/**
 * Enumerable diagnostic catalog.
 */
public interface DiagnosticCatalog {

    /**
     * Returns all codes belonging to this catalog.
     *
     * @return immutable or defensive list of diagnostic codes
     */
    List<DiagnosticCode> codes();
}
