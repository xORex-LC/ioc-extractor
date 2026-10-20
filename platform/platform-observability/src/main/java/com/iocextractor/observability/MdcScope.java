package com.iocextractor.observability;

import java.util.function.Consumer;

/**
 * Binds IOC correlation fields to the shared MDC scope on the current thread.
 * Nested scopes close in reverse order; no context is propagated to workers.
 */
public final class MdcScope implements AutoCloseable {
    private final com.iocextractor.platform.logging.MdcScope delegate =
            com.iocextractor.platform.logging.MdcScope.open();

    private MdcScope() { }

    /**
     * Opens an empty scope. Acquire it before adding fields in a try-with-resources body.
     * @return a scope owned by the calling thread
     */
    public static MdcScope open() {
        return new MdcScope();
    }

    /**
     * Populates a scope for a callback that must return an AutoCloseable, such as
     * an ETL observer. If population fails, restores touched keys before rethrowing;
     * a cleanup failure is suppressed on the original failure.
     *
     * @param initializer writes the caller's explicit correlation fields
     * @return the populated scope; the caller must close it on this thread
     */
    public static MdcScope open(Consumer<MdcScope> initializer) {
        var scope = open();
        try {
            initializer.accept(scope);
            return scope;
        } catch (RuntimeException | Error failure) {
            try {
                scope.close();
            } catch (RuntimeException | Error cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    /**
     * Stores an IOC STRING field, or hides it when value is null.
     * @param field catalogued STRING field
     * @param value correlation value, converted to text for MDC
     * @return this scope
     */
    public MdcScope put(LogField field, Object value) {
        delegate.put(field.spec(), value == null ? null : String.valueOf(value));
        return this;
    }

    /**
     * Hides an ambient field until this scope closes.
     * @param field catalogued field of any scalar type
     * @return this scope
     */
    public MdcScope hide(LogField field) {
        delegate.hide(field.spec());
        return this;
    }

    /** Restores touched keys through the shared scope; repeated successful closes are harmless. */
    @Override
    public void close() {
        delegate.close();
    }
}
