package com.iocextractor.application.artifact;

import com.iocextractor.diagnostics.result.DiagnosticSummary;
import java.util.Objects;

/** Count evidence pinned with prepared rows; retries reproduce the original checkpoint. */
public record DocumentPreparationSummary(int extracted, int retained, DiagnosticSummary diagnostics) {
    public DocumentPreparationSummary {
        Objects.requireNonNull(diagnostics, "diagnostics");
        if (extracted < 0 || retained < 0 || retained > extracted) {
            throw new IllegalArgumentException("Invalid document preparation counts");
        }
    }
}
