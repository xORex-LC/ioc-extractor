package com.iocextractor.platform.diagnostics.result;

import com.iocextractor.platform.diagnostics.DiagnosticSeverity;

/**
 * Strategy that decides whether collected diagnostics should stop processing.
 *
 * <p>Implementations must be pure: no logging, no I/O, no thrown exceptions and
 * no mutation of the supplied notification. Return a non-null decision. The
 * orchestrator must enforce it before committing durable effects; evaluating a
 * policy does not cancel work or throw a service-specific exception.
 */
@FunctionalInterface
public interface FailurePolicy {

    /**
     * Evaluates collected diagnostics.
     *
     * @param notification collected diagnostics
     * @return non-null decision; evaluation leaves notification unchanged
     */
    FailureDecision evaluate(Notification notification);

    /**
     * Returns a policy that stops on the first retained {@code ERROR} or {@code FATAL}
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
