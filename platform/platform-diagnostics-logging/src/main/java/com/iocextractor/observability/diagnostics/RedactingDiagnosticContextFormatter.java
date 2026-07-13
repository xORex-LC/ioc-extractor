package com.iocextractor.observability.diagnostics;

import com.iocextractor.diagnostics.Diagnostic;
import com.iocextractor.diagnostics.DiagnosticContextKeys;
import com.iocextractor.diagnostics.DiagnosticSeverity;
import com.iocextractor.diagnostics.render.DiagnosticContextFormatter;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;

/** Hides raw IOC values from rendered INFO/WARN/ERROR/FATAL diagnostics. */
public final class RedactingDiagnosticContextFormatter implements DiagnosticContextFormatter {

    private static final Set<String> RAW_VALUE_KEYS = Set.of(
            DiagnosticContextKeys.INDICATOR,
            DiagnosticContextKeys.ITEM,
            DiagnosticContextKeys.VALUE);

    @Override
    public String format(Diagnostic diagnostic, String key, Object value) {
        if (allowsRawValue(diagnostic.severity()) || !RAW_VALUE_KEYS.contains(key)) {
            return allowsRawValue(diagnostic.severity())
                    ? String.valueOf(value)
                    : redactEmbeddedRawValues(diagnostic, String.valueOf(value));
        }
        return redacted(String.valueOf(value));
    }

    private static boolean allowsRawValue(DiagnosticSeverity severity) {
        return severity == DiagnosticSeverity.TRACE || severity == DiagnosticSeverity.DEBUG;
    }

    private static String shortHash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 6);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the Java platform", impossible);
        }
    }

    private static String redactEmbeddedRawValues(Diagnostic diagnostic, String text) {
        String redacted = text;
        for (String key : RAW_VALUE_KEYS) {
            Object rawValue = diagnostic.context().get(key);
            if (rawValue == null || String.valueOf(rawValue).isEmpty()) {
                continue;
            }
            String raw = String.valueOf(rawValue);
            redacted = redacted.replace(raw, redacted(raw));
        }
        return redacted;
    }

    private static String redacted(String raw) {
        return "[redacted:sha256:" + shortHash(raw) + "]";
    }
}
