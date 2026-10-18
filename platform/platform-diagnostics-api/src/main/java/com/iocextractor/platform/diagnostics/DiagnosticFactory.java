package com.iocextractor.platform.diagnostics;

import java.time.Clock;
import java.util.Objects;

/**
 * Factory for diagnostics that centralizes timestamp creation behind an
 * explicit clock. It holds no mutable state; sharing it requires a thread-safe clock.
 */
public final class DiagnosticFactory {

    private final Clock clock;

    /**
     * Creates a factory with the supplied clock.
     *
     * @param clock non-null clock used for all diagnostics from this factory
     */
    public DiagnosticFactory(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Starts building a diagnostic for the given code.
     *
     * @param code diagnostic code
     * @return a fresh builder owned by the calling task
     * @throws NullPointerException if code or required code metadata is null
     * @throws IllegalArgumentException if the code ID is blank
     */
    public DiagnosticBuilder create(DiagnosticCode code) {
        return Diagnostic.builder(code, clock);
    }
}
