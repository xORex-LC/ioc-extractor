package com.iocextractor.diagnostics.result;

import com.iocextractor.diagnostics.DiagnosticSeverity;

/**
 * Strategy that decides whether collected diagnostics should stop processing.
 *
 * <p>Implementations must be pure: no logging, no I/O, no thrown exceptions and
 * no mutation of the supplied notification.
 */
@FunctionalInterface
public interface FailurePolicy {

    /**
     * Evaluates collected diagnostics.
     *
     * @param notification collected diagnostics
     * @return decision
     */
    FailureDecision evaluate(Notification notification);

    /**
     * Returns a policy that stops on the first {@code ERROR} or {@code FATAL}
     * diagnostic.
     *
     * @return fail-fast policy
     */
    static FailurePolicy failFast() {
        return notification -> notification.diagnostics().stream()
                .filter(diagnostic -> diagnostic.severity().isErrorOrWorse())
                .findFirst()
                .map(FailureDecision::stop)
                .orElseGet(FailureDecision::continueProcessing);
    }

    /**
     * Returns a policy that collects {@code ERROR} diagnostics and stops only
     * on {@code FATAL}.
     *
     * @return collect-and-continue policy
     */
    static FailurePolicy collectAndContinue() {
        return notification -> notification.diagnostics().stream()
                .filter(diagnostic -> DiagnosticSeverity.FATAL.equals(diagnostic.severity()))
                .findFirst()
                .map(FailureDecision::stop)
                .orElseGet(FailureDecision::continueProcessing);
    }
}
