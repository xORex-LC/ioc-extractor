package com.iocextractor.platform.diagnostics.result;

import com.iocextractor.platform.diagnostics.DiagnosticCode;
import java.util.Objects;

/** Validates marker configuration before a collector accepts any occurrences. */
final class SuppressionCodes {
    private SuppressionCodes() {
    }

    static DiagnosticCode requireValid(DiagnosticCode code) {
        Objects.requireNonNull(code, "suppressionCode");
        if (Objects.requireNonNull(code.id(), "suppressionCode.id").isBlank()) {
            throw new IllegalArgumentException("suppressionCode.id must not be blank");
        }
        Objects.requireNonNull(code.defaultSeverity(), "suppressionCode.defaultSeverity");
        Objects.requireNonNull(code.impact(), "suppressionCode.impact");
        return code;
    }
}
