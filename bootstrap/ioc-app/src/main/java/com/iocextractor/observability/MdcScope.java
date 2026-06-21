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
        return put(field.key(), value);
    }

    public MdcScope put(String key, Object value) {
        Objects.requireNonNull(key, "key");
        if (closed) {
            throw new IllegalStateException("MDC scope is already closed");
        }
        if (!previous.containsKey(key)) {
            previous.put(key, MDC.get(key));
        }
        if (value == null) {
            MDC.remove(key);
        } else {
            MDC.put(key, String.valueOf(value));
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
