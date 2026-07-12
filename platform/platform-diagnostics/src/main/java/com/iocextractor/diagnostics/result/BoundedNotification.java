package com.iocextractor.diagnostics.result;

import com.iocextractor.diagnostics.Diagnostic;
import com.iocextractor.diagnostics.DiagnosticFactory;
import com.iocextractor.diagnostics.DiagnosticSeverity;
import com.iocextractor.diagnostics.codes.PipelineDiagnosticCodes;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * Per-run diagnostic accumulator with a hard retained-item budget.
 *
 * <p>The first rejecting diagnostic is retained even when it arrives after the
 * budget is exhausted. A synthetic summary makes suppression visible.</p>
 */
public final class BoundedNotification {

    private final int limit;
    private final DiagnosticFactory factory;
    private final List<Diagnostic> retained = new ArrayList<>();
    private long suppressed;
    private boolean hasErrorOrWorse;
    private boolean hasFatal;
    private final Map<DiagnosticSeverity, Long> suppressedBySeverity =
            new EnumMap<>(DiagnosticSeverity.class);

    /** Creates an accumulator with a positive retained-item limit. */
    public BoundedNotification(int limit, DiagnosticFactory factory) {
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be positive");
        }
        this.limit = limit;
        this.factory = Objects.requireNonNull(factory, "factory");
    }

    /** Adds a collection in encounter order. */
    public void addAll(Collection<Diagnostic> diagnostics) {
        Objects.requireNonNull(diagnostics, "diagnostics").forEach(this::add);
    }

    /** Adds one diagnostic while preserving the first error and fatal signals. */
    public void add(Diagnostic diagnostic) {
        Objects.requireNonNull(diagnostic, "diagnostic");
        if (retained.size() < limit) {
            retained.add(diagnostic);
            trackRetained(diagnostic);
            return;
        }
        boolean firstError = diagnostic.severity() == DiagnosticSeverity.ERROR && !hasErrorOrWorse;
        boolean firstFatal = diagnostic.severity() == DiagnosticSeverity.FATAL && !hasFatal;
        if (firstError || firstFatal) {
            int replacement = lowestSeverityIndex();
            Diagnostic displaced = retained.set(replacement, diagnostic);
            trackRetained(diagnostic);
            suppress(displaced);
            return;
        }
        suppress(diagnostic);
    }

    /** Returns the bounded snapshot plus a synthetic suppression diagnostic. */
    public List<Diagnostic> diagnostics() {
        if (suppressed == 0) {
            return List.copyOf(retained);
        }
        var result = new ArrayList<>(retained);
        result.add(factory.create(PipelineDiagnosticCodes.DIAGNOSTICS_SUPPRESSED)
                .severity(highestSuppressedSeverity())
                .with("limit", limit)
                .with("suppressedCount", suppressed)
                .with("suppressedBySeverity", stringCounts())
                .build());
        return List.copyOf(result);
    }

    /** Returns counts for all observed diagnostics, including suppressed ones. */
    public DiagnosticSummary summary() {
        return DiagnosticSummary.of(retained, suppressedBySeverity);
    }

    private void suppress(Diagnostic diagnostic) {
        suppressed++;
        suppressedBySeverity.merge(diagnostic.severity(), 1L, Long::sum);
    }

    private void trackRetained(Diagnostic diagnostic) {
        hasErrorOrWorse |= diagnostic.severity().isErrorOrWorse();
        hasFatal |= diagnostic.severity() == DiagnosticSeverity.FATAL;
    }

    private int lowestSeverityIndex() {
        int selected = 0;
        for (int index = 1; index < retained.size(); index++) {
            if (retained.get(index).severity().compareTo(retained.get(selected).severity()) < 0) {
                selected = index;
            }
        }
        return selected;
    }

    private DiagnosticSeverity highestSuppressedSeverity() {
        return suppressedBySeverity.keySet().stream()
                .max(Enum::compareTo)
                .orElse(DiagnosticSeverity.WARN);
    }

    private Map<String, Long> stringCounts() {
        var counts = new java.util.LinkedHashMap<String, Long>();
        suppressedBySeverity.forEach((severity, count) -> counts.put(severity.name(), count));
        return Map.copyOf(counts);
    }
}
