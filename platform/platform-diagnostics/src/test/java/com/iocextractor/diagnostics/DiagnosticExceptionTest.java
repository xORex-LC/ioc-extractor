package com.iocextractor.diagnostics;

import org.junit.jupiter.api.Test;
import com.iocextractor.platform.diagnostics.Diagnostic;
import com.iocextractor.diagnostics.codes.SourceDiagnosticCodes;
import com.iocextractor.common.IocExtractorException;
import java.time.Clock;
import static org.assertj.core.api.Assertions.assertThat;

import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class DiagnosticExceptionTest {

    @Test
    void rejectsMissingDiagnosticAtConstructionBoundary() {
        assertThatNullPointerException()
                .isThrownBy(() -> new DiagnosticException(null))
                .withMessage("diagnostic");
    }
    @Test
    void preserves_the_product_exception_boundary_and_original_cause() {
        var cause = new IllegalStateException("read failed");
        var diagnostic = Diagnostic.builder(SourceDiagnosticCodes.READ_FAILED, Clock.systemUTC()).cause(cause).build();
        var failure = new DiagnosticException(diagnostic);
        assertThat(failure).isInstanceOf(IocExtractorException.class).hasCause(cause).hasMessage("SOURCE.READ_FAILED");
        assertThat(failure.diagnostic()).isSameAs(diagnostic);
        var contextual = new DiagnosticException(Diagnostic.builder(SourceDiagnosticCodes.READ_FAILED, Clock.systemUTC())
                .with("source", "input.txt").build());
        assertThat(contextual).hasMessage("SOURCE.READ_FAILED {source=input.txt}");
    }
}
