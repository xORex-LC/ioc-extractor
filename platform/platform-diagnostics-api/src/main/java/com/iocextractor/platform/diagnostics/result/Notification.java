package com.iocextractor.platform.diagnostics.result;

import com.iocextractor.platform.diagnostics.Diagnostic;
import com.iocextractor.platform.diagnostics.DiagnosticSeverity;

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

    /** Creates an empty, unbounded notification. */
    public Notification() {
    }

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
     * Adds occurrences in encounter order, without deduplication. Null elements
     * are rejected; entries added before a failure remain collected.
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

}
