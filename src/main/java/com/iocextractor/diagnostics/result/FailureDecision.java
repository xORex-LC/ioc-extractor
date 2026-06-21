package com.iocextractor.diagnostics.result;

import com.iocextractor.diagnostics.Diagnostic;

import java.util.Objects;
import java.util.Optional;

/**
 * Pure decision returned by a {@link FailurePolicy}.
 */
public final class FailureDecision {

    private static final FailureDecision CONTINUE = new FailureDecision(false, null);

    private final boolean stop;
    private final Diagnostic diagnostic;

    private FailureDecision(boolean stop, Diagnostic diagnostic) {
        this.stop = stop;
        this.diagnostic = diagnostic;
    }

    /**
     * Returns a continue decision.
     *
     * @return continue decision
     */
    public static FailureDecision continueProcessing() {
        return CONTINUE;
    }

    /**
     * Returns a stop decision caused by the supplied diagnostic.
     *
     * @param diagnostic diagnostic that triggered the stop
     * @return stop decision
     */
    public static FailureDecision stop(Diagnostic diagnostic) {
        return new FailureDecision(true, Objects.requireNonNull(diagnostic, "diagnostic"));
    }

    /**
     * Returns whether processing should stop.
     *
     * @return {@code true} when the caller should stop
     */
    public boolean shouldStop() {
        return stop;
    }

    /**
     * Returns the diagnostic that caused a stop decision.
     *
     * @return stop diagnostic, or empty for continue
     */
    public Optional<Diagnostic> diagnostic() {
        return Optional.ofNullable(diagnostic);
    }
}
