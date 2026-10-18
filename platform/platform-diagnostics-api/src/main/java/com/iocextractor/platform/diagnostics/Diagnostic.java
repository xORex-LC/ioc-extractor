package com.iocextractor.platform.diagnostics;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable data object describing a processing diagnostic.
 *
 * <p>Equality is intentionally based on the code object, context and effective severity.
 * Timestamp and cause are excluded. Collectors still count every occurrence;
 * equality does not deduplicate their input. Context values and the cause are
 * shared references, so callers must not mutate them after publication.
 */
public final class Diagnostic {

    private final DiagnosticCode code;
    private final DiagnosticSeverity severity;
    private final Map<String, Object> context;
    private final Throwable cause;
    private final Instant timestamp;

    Diagnostic(DiagnosticCode code, DiagnosticSeverity severity, Map<String, Object> context,
               Throwable cause, Instant timestamp) {
        this.code = Objects.requireNonNull(code, "code");
        this.severity = Objects.requireNonNull(severity, "severity");
        this.context = Map.copyOf(Objects.requireNonNull(context, "context"));
        this.cause = cause;
        this.timestamp = Objects.requireNonNull(timestamp, "timestamp");
    }

    /**
     * Starts building a diagnostic with a caller-provided clock.
     *
     * @param code diagnostic code
     * @param clock clock used for the timestamp
     * @return a fresh builder owned by this task
     * @throws NullPointerException if code, clock or required code metadata is null
     * @throws IllegalArgumentException if the code ID is blank
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

    /**
     * Compares the code object, context and severity, excluding time and cause.
     * @param other candidate diagnostic
     * @return whether the diagnostic values match
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Diagnostic that)) {
            return false;
        }
        return code.equals(that.code) && context.equals(that.context) && severity == that.severity;
    }

    /** Hashes the same components used by equality.
     * @return a hash of code, context and severity */
    @Override
    public int hashCode() {
        return Objects.hash(code, context, severity);
    }

    /** Formats the occurrence for inspection.
     * @return diagnostic details, including unredacted context; not a safe log format */
    @Override
    public String toString() {
        return "Diagnostic{"
                + "code=" + code.id()
                + ", severity=" + severity
                + ", context=" + context
                + ", timestamp=" + timestamp
                + '}';
    }
}
