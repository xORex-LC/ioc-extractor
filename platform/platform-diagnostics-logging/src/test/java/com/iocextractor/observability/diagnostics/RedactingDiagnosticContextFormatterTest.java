package com.iocextractor.observability.diagnostics;

import com.iocextractor.diagnostics.Diagnostic;
import com.iocextractor.diagnostics.DiagnosticSeverity;
import com.iocextractor.diagnostics.codes.SinkDiagnosticCodes;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;

class RedactingDiagnosticContextFormatterTest {

    private static final String RAW = "https://user:password@example.test/path?token=secret#fragment";
    private final RedactingDiagnosticContextFormatter formatter = new RedactingDiagnosticContextFormatter();

    @ParameterizedTest
    @EnumSource(DiagnosticSeverity.class)
    void sanitizesSensitiveUrlComponentsAtEverySeverity(DiagnosticSeverity severity) {
        Diagnostic diagnostic = diagnostic(severity);

        String rendered = formatter.format(diagnostic, "indicator", RAW);

        assertThat(rendered).doesNotContain("user", "password", "token=secret");
        if (severity == DiagnosticSeverity.DEBUG || severity == DiagnosticSeverity.TRACE) {
            assertThat(rendered)
                    .isEqualTo("https://<redacted>@example.test/path?<redacted>#fragment");
        } else {
            assertThat(rendered).startsWith("[redacted:sha256:").endsWith("]");
        }
    }

    @ParameterizedTest
    @EnumSource(DiagnosticSeverity.class)
    void appliesTheSamePolicyToEmbeddedRawValues(DiagnosticSeverity severity) {
        Diagnostic diagnostic = diagnostic(severity);

        String rendered = formatter.format(diagnostic, "reason", "invalid row for " + RAW);

        assertThat(rendered).doesNotContain("password", "token=secret");
        if (severity == DiagnosticSeverity.DEBUG || severity == DiagnosticSeverity.TRACE) {
            assertThat(rendered).contains("https://<redacted>@example.test/path?<redacted>#fragment");
        } else {
            assertThat(rendered).contains("[redacted:sha256:");
        }
        assertThat(diagnostic.context()).containsEntry("indicator", RAW);
    }

    @ParameterizedTest
    @EnumSource(value = DiagnosticSeverity.class, names = {"FATAL", "ERROR", "WARN", "INFO"})
    void hashesTheOriginalValueRatherThanTheSanitizedForm(DiagnosticSeverity severity) {
        String sameBaseDifferentSecret = "https://user:other@example.test/path?token=different#fragment";

        String first = formatter.format(diagnostic(severity), "indicator", RAW);
        Diagnostic secondDiagnostic = Diagnostic.builder(SinkDiagnosticCodes.ROW_MAPPING_FAILED, Clock.systemUTC())
                .severity(severity)
                .with("sink", "masks")
                .with("indicator", sameBaseDifferentSecret)
                .with("reason", "invalid")
                .build();
        String second = formatter.format(secondDiagnostic, "indicator", sameBaseDifferentSecret);

        assertThat(first).isNotEqualTo(second);
    }

    private Diagnostic diagnostic(DiagnosticSeverity severity) {
        return Diagnostic.builder(SinkDiagnosticCodes.ROW_MAPPING_FAILED, Clock.systemUTC())
                .severity(severity)
                .with("sink", "masks")
                .with("indicator", RAW)
                .with("reason", "invalid row for " + RAW)
                .build();
    }
}
