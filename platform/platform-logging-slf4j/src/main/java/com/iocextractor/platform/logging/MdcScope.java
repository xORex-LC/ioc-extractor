package com.iocextractor.platform.logging;

import org.slf4j.MDC;

import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.SequencedMap;

/**
 * Restores the MDC keys touched during an operation on the current thread.
 *
 * <p>Open, populate and close the scope on the same thread. Nested scopes must
 * close in LIFO order. Each key's first previous value is restored; writes to
 * untouched keys are left alone. No executor or network context propagation is
 * performed, and a provider without MDC support cannot supply context delivery.
 *
 * <p>Acquire the resource before writing to it, so a failed write still attempts
 * cleanup:
 * <pre>{@code
 * try (var scope = MdcScope.open()) {
 *     scope.put(taskId, "task-42");
 *     // Run this operation on the current thread.
 * }
 * }</pre>
 * Provider failures propagate; restoration cannot be guaranteed if the provider
 * itself fails during cleanup. This class is not thread-safe.
 */
public final class MdcScope implements AutoCloseable {
    private final SequencedMap<String, String> originalValues = new LinkedHashMap<>();
    private boolean closed;

    private MdcScope() { }

    /**
     * Creates an empty scope without reading or changing MDC.
     *
     * @return a scope owned by the current thread
     */
    public static MdcScope open() {
        return new MdcScope();
    }

    /**
     * Sets one STRING field, remembering its value before this scope first touched it.
     *
     * @param field STRING field declaration
     * @param value string to store, or null to hide the key for this scope
     * @return this scope
     * @throws NullPointerException if field is null
     * @throws IllegalArgumentException if field is not declared STRING
     * @throws IllegalStateException if this scope has already closed successfully
     */
    public MdcScope put(LogFieldSpec field, String value) {
        Objects.requireNonNull(field, "field");
        if (field.valueType() != LogValueType.STRING) {
            throw new IllegalArgumentException("MDC requires STRING; field " + field.key()
                    + " declares " + field.valueType());
        }
        change(field.key(), value);
        return this;
    }

    /**
     * Temporarily removes an ambient key, including one declared LONG or BOOLEAN.
     *
     * @param field field whose ambient string would conflict with event-local data
     * @return this scope
     * @throws NullPointerException if field is null
     * @throws IllegalStateException if this scope has already closed successfully
     */
    public MdcScope hide(LogFieldSpec field) {
        Objects.requireNonNull(field, "field");
        change(field.key(), null);
        return this;
    }

    private void change(String key, String value) {
        if (closed) {
            throw new IllegalStateException("MDC scope is closed");
        }
        if (!originalValues.containsKey(key)) {
            originalValues.put(key, MDC.get(key));
        }
        if (value != null) {
            MDC.put(key, value);
        } else {
            MDC.remove(key);
        }
    }

    /**
     * Restores touched keys in reverse order, preserving unrelated MDC changes.
     * Calling this again after a successful close has no effect. Provider failures
     * propagate, including normal suppression when a try-with-resources body failed.
     */
    @Override
    public void close() {
        if (!closed) {
            for (var saved : originalValues.reversed().entrySet()) {
                if (saved.getValue() != null) {
                    MDC.put(saved.getKey(), saved.getValue());
                } else {
                    MDC.remove(saved.getKey());
                }
            }
            closed = true;
        }
    }
}
