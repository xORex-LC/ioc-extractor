package com.iocextractor.diagnostics.catalog;

import com.iocextractor.diagnostics.DiagnosticCategory;
import com.iocextractor.diagnostics.IocDiagnosticCode;
import com.iocextractor.platform.diagnostics.DiagnosticImpact;
import com.iocextractor.platform.diagnostics.DiagnosticSeverity;

import java.util.Objects;

/**
 * Flattened diagnostic catalog entry used for generated documentation and
 * validation tests.
 *
 * @param id stable diagnostic id
 * @param category diagnostic category
 * @param defaultSeverity default severity
 * @param impact affected processing unit
 * @param messageKey stable message key
 * @param defaultMessageTemplate fallback message template
 */
public record DiagnosticCatalogEntry(
        String id,
        DiagnosticCategory category,
        DiagnosticSeverity defaultSeverity,
        DiagnosticImpact impact,
        String messageKey,
        String defaultMessageTemplate
) {

    /**
     * Creates an entry from a diagnostic code.
     *
     * @param code diagnostic code
     * @return catalog entry
     */
    public static DiagnosticCatalogEntry from(IocDiagnosticCode code) {
        Objects.requireNonNull(code, "code");
        return new DiagnosticCatalogEntry(
                code.id(),
                code.category(),
                code.defaultSeverity(),
                code.impact(),
                code.messageKey(),
                code.defaultMessageTemplate());
    }
}
