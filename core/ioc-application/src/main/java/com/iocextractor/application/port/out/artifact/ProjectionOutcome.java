package com.iocextractor.application.port.out.artifact;

import com.iocextractor.diagnostics.Diagnostic;

import java.util.List;
import java.util.Objects;

/**
 * Immutable outcome of a successfully installed derived artifact.
 *
 * <p>Diagnostics are advisory by contract because the canonical commit has
 * already happened. A hard projection failure must be thrown instead of being
 * returned as an error diagnostic.</p>
 *
 * @param projectedRows rows materialized into the derived artifact
 * @param diagnostics advisory diagnostics observed while projecting
 */
public record ProjectionOutcome(int projectedRows, List<Diagnostic> diagnostics) {

    /** Validates row counts and the post-commit advisory-only invariant. */
    public ProjectionOutcome {
        if (projectedRows < 0) {
            throw new IllegalArgumentException("projectedRows must not be negative");
        }
        diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
        if (diagnostics.stream().anyMatch(diagnostic -> diagnostic.severity().isErrorOrWorse())) {
            throw new IllegalArgumentException("Projection outcome diagnostics must be advisory");
        }
    }

    /** Returns a successful projection outcome without diagnostics. */
    public static ProjectionOutcome clean(int projectedRows) {
        return new ProjectionOutcome(projectedRows, List.of());
    }
}
