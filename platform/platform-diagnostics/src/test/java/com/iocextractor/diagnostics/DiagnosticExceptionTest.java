package com.iocextractor.diagnostics;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class DiagnosticExceptionTest {

    @Test
    void rejectsMissingDiagnosticAtConstructionBoundary() {
        assertThatNullPointerException()
                .isThrownBy(() -> new DiagnosticException(null))
                .withMessage("diagnostic");
    }
}
