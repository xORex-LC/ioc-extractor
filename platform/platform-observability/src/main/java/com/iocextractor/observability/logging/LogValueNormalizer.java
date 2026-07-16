package com.iocextractor.observability.logging;

import com.iocextractor.observability.LogField;

import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;

/**
 * Normalizes values to the scalar type declared by {@link LogField}.
 */
final class LogValueNormalizer {

    private LogValueNormalizer() {
    }

    static Object normalize(LogField field, Object value) {
        Objects.requireNonNull(field, "field");
        if (value == null) {
            return null;
        }
        return switch (field.valueType()) {
            case STRING -> normalizeString(field, value);
            case LONG -> normalizeLong(field, value);
            case BOOLEAN -> normalizeBoolean(field, value);
        };
    }

    private static String normalizeString(LogField field, Object value) {
        if (value instanceof CharSequence
                || value instanceof Enum<?>
                || value instanceof Path
                || value instanceof UUID) {
            return value.toString();
        }
        throw mismatch(field, value);
    }

    private static Long normalizeLong(LogField field, Object value) {
        if (value instanceof Byte || value instanceof Short
                || value instanceof Integer || value instanceof Long) {
            return ((Number) value).longValue();
        }
        throw mismatch(field, value);
    }

    private static Boolean normalizeBoolean(LogField field, Object value) {
        if (value instanceof Boolean booleanValue) {
            return booleanValue;
        }
        throw mismatch(field, value);
    }

    private static IllegalArgumentException mismatch(LogField field, Object value) {
        return new IllegalArgumentException("Field " + field.key() + " requires "
                + field.valueType() + " but received " + value.getClass().getName());
    }
}
