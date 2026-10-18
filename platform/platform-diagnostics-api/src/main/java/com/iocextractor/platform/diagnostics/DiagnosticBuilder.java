package com.iocextractor.platform.diagnostics;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Builder for {@link Diagnostic} instances.
 *
 * <p>The builder requires an explicit {@link Clock}; diagnostics do not call
 * {@code Instant.now()} internally, which keeps tests deterministic. A builder
 * belongs to one task and is not thread-safe. Build snapshots copy the context
 * map; its values and the cause remain shared references. All mutator arguments
 * must be non-null. A failed bulk context addition may have added earlier entries.
 */
public final class DiagnosticBuilder {

    private final DiagnosticCode code;
    private final Clock clock;
    private final Map<String, Object> context = new LinkedHashMap<>();
    private DiagnosticSeverity severity;
    private Throwable cause;

    DiagnosticBuilder(DiagnosticCode code, Clock clock) {
        this.code = Objects.requireNonNull(code, "code");
        this.clock = Objects.requireNonNull(clock, "clock");
        validateCode(code);
        this.severity = code.defaultSeverity();
    }

    static void validateCode(DiagnosticCode code) {
        Objects.requireNonNull(code, "code");
        if (Objects.requireNonNull(code.id(), "code.id").isBlank()) {
            throw new IllegalArgumentException("code.id must not be blank");
        }
        Objects.requireNonNull(code.defaultSeverity(), "code.defaultSeverity");
        Objects.requireNonNull(code.impact(), "code.impact");
    }

    /**
     * Overrides the default severity from the diagnostic code.
     *
     * @param severity effective severity
     * @return this builder
     */
    public DiagnosticBuilder severity(DiagnosticSeverity severity) {
        this.severity = Objects.requireNonNull(severity, "severity");
        return this;
    }

    /**
     * Adds one structured context value.
     *
     * @param key non-null context key
     * @param value non-null context value
     * @return this builder
     */
    public DiagnosticBuilder with(String key, Object value) {
        context.put(Objects.requireNonNull(key, "key"), Objects.requireNonNull(value, "value"));
        return this;
    }

    /**
     * Adds all structured context values.
     *
     * @param values context values
     * @return this builder
     */
    public DiagnosticBuilder context(Map<String, ?> values) {
        Objects.requireNonNull(values, "values").forEach(this::with);
        return this;
    }

    /**
     * Attaches the underlying failure. Omit this call when there is no cause.
     *
     * @param cause non-null underlying failure
     * @return this builder
     */
    public DiagnosticBuilder cause(Throwable cause) {
        this.cause = Objects.requireNonNull(cause, "cause");
        return this;
    }

    /**
     * Builds a diagnostic using the configured clock.
     *
     * @return immutable diagnostic; later builder changes do not change its map
     * @throws NullPointerException if the supplied clock returns null
     */
    public Diagnostic build() {
        return new Diagnostic(code, severity, context, cause, clock.instant());
    }
}
