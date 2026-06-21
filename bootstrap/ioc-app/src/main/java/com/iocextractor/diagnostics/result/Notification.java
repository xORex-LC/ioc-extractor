package com.iocextractor.diagnostics.result;

import com.iocextractor.diagnostics.Diagnostic;
import com.iocextractor.diagnostics.DiagnosticException;
import com.iocextractor.diagnostics.DiagnosticSeverity;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * Mutable collector for diagnostics produced within one run or item scope.
 *
 * <p>This type is intentionally not thread-safe. A concurrent pipeline should
 * create one notification per task and aggregate them at an orchestration
 * boundary.
 */
public final class Notification {

    private final List<Diagnostic> diagnostics = new ArrayList<>();

    /**
     * Adds one diagnostic.
     *
     * @param diagnostic diagnostic to add
     * @return this notification
     */
    public Notification add(Diagnostic diagnostic) {
        diagnostics.add(Objects.requireNonNull(diagnostic, "diagnostic"));
        return this;
    }

    /**
     * Adds all diagnostics.
     *
     * @param diagnostics diagnostics to add
     * @return this notification
     */
    public Notification addAll(Collection<Diagnostic> diagnostics) {
        Objects.requireNonNull(diagnostics, "diagnostics").forEach(this::add);
        return this;
    }

    /**
     * Returns collected diagnostics.
     *
     * @return immutable snapshot
     */
    public List<Diagnostic> diagnostics() {
        return List.copyOf(diagnostics);
    }

    /**
     * Returns whether any diagnostic is {@code ERROR} or {@code FATAL}.
     *
     * @return {@code true} when errors are present
     */
    public boolean hasErrors() {
        return diagnostics.stream()
                .map(Diagnostic::severity)
                .anyMatch(DiagnosticSeverity::isErrorOrWorse);
    }

    /**
     * Returns whether any diagnostic is {@code FATAL}.
     *
     * @return {@code true} when fatal diagnostics are present
     */
    public boolean hasFatal() {
        return diagnostics.stream()
                .map(Diagnostic::severity)
                .anyMatch(DiagnosticSeverity.FATAL::equals);
    }

    /**
     * Converts this notification into a result for the supplied value.
     *
     * @param value processed value
     * @param <T> value type
     * @return result containing current diagnostics
     */
    public <T> Result<T> toResult(T value) {
        return Result.of(value, diagnostics);
    }

    /**
     * Evaluates this notification with the supplied policy and throws only when
     * the policy returns a stop decision.
     *
     * @param policy failure policy
     * @throws DiagnosticException when the policy requests stop
     */
    public void throwIfRejected(FailurePolicy policy) {
        var decision = Objects.requireNonNull(policy, "policy").evaluate(this);
        if (decision.shouldStop()) {
            throw new DiagnosticException(decision.diagnostic()
                    .orElseThrow(() -> new IllegalStateException("Stop decision requires diagnostic")));
        }
    }
}
