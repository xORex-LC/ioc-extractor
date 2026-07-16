package com.iocextractor.observability;

import org.slf4j.MDC;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Try-with-resources scope for MDC values.
 *
 * <p>The scope restores only keys it changed. Nested scopes are supported by
 * storing previous values before each write.
 */
public final class MdcScope implements AutoCloseable {

    private final Map<String, String> previous = new LinkedHashMap<>();
    private boolean closed;

    private MdcScope() {
    }

    public static MdcScope open() {
        return new MdcScope();
    }

    public MdcScope put(LogField field, Object value) {
        Objects.requireNonNull(field, "field");
        if (field.valueType() != LogValueType.STRING) {
            throw new IllegalArgumentException(
                    "MDC accepts only STRING fields; " + field.key() + " is " + field.valueType());
        }
        return put(field.key(), value == null ? null : String.valueOf(value));
    }

    /**
     * Temporarily removes one catalogued field from MDC.
     *
     * <p>The previous value is restored when the scope closes. This supports
     * event-local structured fields that intentionally override ambient MDC.
     *
     * @param field field to hide
     * @return this scope
     */
    public MdcScope hide(LogField field) {
        Objects.requireNonNull(field, "field");
        return put(field.key(), null);
    }

    private MdcScope put(String key, String value) {
        if (closed) {
            throw new IllegalStateException("MDC scope is already closed");
        }
        if (!previous.containsKey(key)) {
            previous.put(key, MDC.get(key));
        }
        if (value == null) {
            MDC.remove(key);
        } else {
            MDC.put(key, value);
        }
        return this;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        var keys = previous.keySet().stream().toList();
        for (int i = keys.size() - 1; i >= 0; i--) {
            var key = keys.get(i);
            var value = previous.get(key);
            if (value == null) {
                MDC.remove(key);
            } else {
                MDC.put(key, value);
            }
        }
        closed = true;
    }
}
