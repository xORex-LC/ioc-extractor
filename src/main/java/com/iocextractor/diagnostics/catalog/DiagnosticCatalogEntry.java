package com.iocextractor.diagnostics.catalog;

import com.iocextractor.diagnostics.DiagnosticCategory;
import com.iocextractor.diagnostics.DiagnosticCode;
import com.iocextractor.diagnostics.DiagnosticSeverity;

import java.util.Objects;

/**
 * Flattened diagnostic catalog entry used for generated documentation and
 * validation tests.
 *
 * @param id stable diagnostic id
 * @param category diagnostic category
 * @param defaultSeverity default severity
 * @param messageKey stable message key
 * @param defaultMessageTemplate fallback message template
 */
public record DiagnosticCatalogEntry(
        String id,
        DiagnosticCategory category,
        DiagnosticSeverity defaultSeverity,
        String messageKey,
        String defaultMessageTemplate
) {

    /**
     * Creates an entry from a diagnostic code.
     *
     * @param code diagnostic code
     * @return catalog entry
     */
    public static DiagnosticCatalogEntry from(DiagnosticCode code) {
        Objects.requireNonNull(code, "code");
        return new DiagnosticCatalogEntry(
                code.id(),
                code.category(),
                code.defaultSeverity(),
                code.messageKey(),
                code.defaultMessageTemplate());
    }
}
