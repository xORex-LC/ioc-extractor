package com.iocextractor.platform.diagnostics.result;

import com.iocextractor.platform.diagnostics.Diagnostic;
import com.iocextractor.platform.diagnostics.DiagnosticSeverity;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * Processing value paired with diagnostics collected while producing it.
 * The list is copied; the value, context values and causes are not deep-copied.
 * The record's generated equality includes both components. This type neither
 * delivers diagnostics nor enforces a failure policy.
 *
 * @param value processed value; may be {@code null} when diagnostics explain why
 *              no value was produced
 * @param diagnostics diagnostics associated with the value
 * @param <T> value type
 */
public record Result<T>(T value, List<Diagnostic> diagnostics) {

    /**
     * Creates a result with defensive diagnostics copying.
     *
     * @param value processed value
     * @param diagnostics diagnostics associated with the value
     */
    public Result {
        diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
    }

    /**
     * Creates a successful result without diagnostics.
     *
     * @param value processed value
     * @param <T> value type
     * @return result
     */
    public static <T> Result<T> success(T value) {
        return new Result<>(value, List.of());
    }

    /**
     * Creates a result from value and diagnostics.
     *
     * @param value processed value
     * @param diagnostics diagnostics associated with the value
     * @param <T> value type
     * @return result
     */
    public static <T> Result<T> of(T value, Collection<Diagnostic> diagnostics) {
        return new Result<>(value, List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics")));
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
     * Returns a copy with one additional diagnostic.
     *
     * @param diagnostic diagnostic to append
     * @return new result
     */
    public Result<T> withDiagnostic(Diagnostic diagnostic) {
        Objects.requireNonNull(diagnostic, "diagnostic");
        var next = new java.util.ArrayList<>(diagnostics);
        next.add(diagnostic);
        return new Result<>(value, next);
    }

    /**
     * Maps the value while preserving diagnostics. If the current value is
     * {@code null}, the mapper is not called and {@code null} is propagated.
     *
     * @param mapper value mapper
     * @param <R> mapped type
     * @return mapped result, retaining diagnostic order
     * @throws NullPointerException if mapper is null, even when value is null
     * @throws RuntimeException if the mapper throws one; it propagates unchanged
     */
    public <R> Result<R> map(Function<T, R> mapper) {
        Objects.requireNonNull(mapper, "mapper");
        return new Result<>(value == null ? null : mapper.apply(value), diagnostics);
    }
}
