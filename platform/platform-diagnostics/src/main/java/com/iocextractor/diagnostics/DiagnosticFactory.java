package com.iocextractor.diagnostics;

import java.time.Clock;
import java.util.Objects;

/**
 * Factory for diagnostics that centralizes timestamp creation behind an
 * explicit clock.
 */
public final class DiagnosticFactory {

    private final Clock clock;

    /**
     * Creates a factory with the supplied clock.
     *
     * @param clock clock used for all diagnostics from this factory
     */
    public DiagnosticFactory(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Starts building a diagnostic for the given code.
     *
     * @param code diagnostic code
     * @return diagnostic builder
     */
    public DiagnosticBuilder create(DiagnosticCode code) {
        return Diagnostic.builder(code, clock);
    }
}
