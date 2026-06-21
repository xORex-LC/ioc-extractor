package com.iocextractor.diagnostics;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable data object describing a processing diagnostic.
 *
 * <p>Equality is intentionally based only on {@code code + context}. Severity,
 * cause and timestamp describe delivery/runtime aspects of the same fact and
 * must not prevent deduplication.
 */
public final class Diagnostic {

    private final DiagnosticCode code;
    private final DiagnosticSeverity severity;
    private final DiagnosticCategory category;
    private final Map<String, Object> context;
    private final Throwable cause;
    private final Instant timestamp;

    Diagnostic(DiagnosticCode code, DiagnosticSeverity severity, Map<String, Object> context,
               Throwable cause, Instant timestamp) {
        this.code = Objects.requireNonNull(code, "code");
        this.severity = Objects.requireNonNull(severity, "severity");
        this.category = Objects.requireNonNull(code.category(), "code.category");
        this.context = Map.copyOf(Objects.requireNonNull(context, "context"));
        this.cause = cause;
        this.timestamp = Objects.requireNonNull(timestamp, "timestamp");
    }

    /**
     * Starts building a diagnostic with a caller-provided clock.
     *
     * @param code diagnostic code
     * @param clock clock used for the timestamp
     * @return diagnostic builder
     */
    public static DiagnosticBuilder builder(DiagnosticCode code, Clock clock) {
        return new DiagnosticBuilder(code, clock);
    }

    /**
     * Returns the stable diagnostic code.
     *
     * @return diagnostic code
     */
    public DiagnosticCode code() {
        return code;
    }

    /**
     * Returns the effective severity.
     *
     * @return effective severity
     */
    public DiagnosticSeverity severity() {
        return severity;
    }

    /**
     * Returns the category derived from {@link #code()}.
     *
     * @return diagnostic category
     */
    public DiagnosticCategory category() {
        return category;
    }

    /**
     * Returns structured producer-supplied context.
     *
     * @return immutable context map
     */
    public Map<String, Object> context() {
        return context;
    }

    /**
     * Returns the optional underlying cause.
     *
     * @return optional cause
     */
    public Optional<Throwable> cause() {
        return Optional.ofNullable(cause);
    }

    /**
     * Returns the creation timestamp.
     *
     * @return timestamp from the builder/factory clock
     */
    public Instant timestamp() {
        return timestamp;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Diagnostic that)) {
            return false;
        }
        return code.equals(that.code) && context.equals(that.context);
    }

    @Override
    public int hashCode() {
        return Objects.hash(code, context);
    }

    @Override
    public String toString() {
        return "Diagnostic{"
                + "code=" + code.id()
                + ", severity=" + severity
                + ", category=" + category
                + ", context=" + context
                + ", timestamp=" + timestamp
                + '}';
    }
}
