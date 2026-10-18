package com.iocextractor.platform.diagnostics.result;

import com.iocextractor.platform.diagnostics.Diagnostic;
import com.iocextractor.platform.diagnostics.DiagnosticFactory;
import com.iocextractor.platform.diagnostics.DiagnosticImpact;
import com.iocextractor.platform.diagnostics.DiagnosticSeverity;
import com.iocextractor.platform.diagnostics.DiagnosticCode;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Per-run diagnostic accumulator with a retained-item budget for element and run occurrences.
 *
 * <p>The first rejecting diagnostic is retained even when it arrives after the
 * budget is exhausted. Low-cardinality operation diagnostics remain visible
 * outside that budget. A synthetic summary makes suppression visible.</p>
 * <p>This mutable accumulator belongs to one task and is not thread-safe. It
 * counts occurrences without deduplication. First ERROR/FATAL samples replace
 * the earliest lowest-severity budgeted sample when needed. It retains at most
 * limit ELEMENT/RUN samples plus every OPERATION occurrence, so total memory is
 * not bounded when callers produce unbounded OPERATION diagnostics.</p>
 * <p>Replacement keeps the displaced sample's list position. Snapshot order can
 * therefore differ from encounter order; it does not identify the earliest
 * rejecting occurrence in the original input.</p>
 */
public final class BoundedNotification {

    private final int limit;
    private long observed;
    private final DiagnosticCode suppressionCode;
    private final DiagnosticFactory factory;
    private final List<Diagnostic> retained = new ArrayList<>();
    private int budgetedRetained;
    private long suppressed;
    private boolean hasErrorOrWorse;
    private boolean hasFatal;
    private final Map<DiagnosticSeverity, Long> suppressedBySeverity =
            new EnumMap<>(DiagnosticSeverity.class);

    /**
     * Creates a run accumulator with an application-owned suppression marker.
     * @param limit positive ELEMENT/RUN detail budget
     * @param factory non-null factory for synthetic snapshot markers
     * @param suppressionCode valid code reserved throughout the aggregation tree
     * @throws IllegalArgumentException if limit is not positive or code ID is blank
     * @throws NullPointerException if an argument or required code metadata is null
     */
    public BoundedNotification(int limit, DiagnosticFactory factory, DiagnosticCode suppressionCode) {
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be positive");
        }
        this.suppressionCode = SuppressionCodes.requireValid(suppressionCode);
        this.limit = limit;
        this.factory = Objects.requireNonNull(factory, "factory");
    }

    /**
     * Adds occurrences in encounter order; earlier additions remain on failure.
     * @param diagnostics non-null collection with no null elements or synthetic markers
     */
    public void addAll(Collection<Diagnostic> diagnostics) {
        Objects.requireNonNull(diagnostics, "diagnostics").forEach(this::add);
    }

    /**
     * Merges retained samples and exact omitted counts without delivering them.
     * @param batch non-null independently bounded batch using the same marker
     */
    public void addAll(DiagnosticBatch batch) {
        addAll(batch, diagnostic -> { });
    }

    /**
     * Merges a batch and delivers each newly retained occurrence synchronously.
     * A later replacement does not undo an earlier delivery, so total delivery
     * calls can exceed the retention limit. A raw callback failure propagates
     * after partial mutation; this method does not retry or roll back.
     * Retained occurrences are processed before the batch's omitted counts.
     * If delivery fails, the failing occurrence is already counted and retained,
     * but later occurrences and omitted counts have not been added. Overflow
     * also propagates without rolling back earlier accepted counts.
     * @param batch non-null batch made with the same stable marker code
     * @param delivery non-null callback; use a resilient sink when delivery must not abort work
     * @throws IllegalArgumentException if retained detail contains this accumulator's marker
     * @throws ArithmeticException if an observed count exceeds long range
     */
    public void addAll(DiagnosticBatch batch, Consumer<Diagnostic> delivery) {
        Objects.requireNonNull(batch, "batch");
        Objects.requireNonNull(delivery, "delivery");
        // Check the whole retained list before changing this accumulator. A batch
        // built with another marker configuration cannot smuggle our marker in.
        batch.retained().forEach(this::requireOccurrence);
        for (Diagnostic diagnostic : batch.retained()) {
            if (offer(diagnostic)) {
                delivery.accept(diagnostic);
            }
        }
        batch.suppressedBySeverity().forEach((severity, count) -> {
            observed = Math.addExact(observed, count);
            suppressed = Math.addExact(suppressed, count);
            suppressedBySeverity.merge(severity, count, Math::addExact);
        });
    }

    /**
     * Adds one occurrence under the same rules as {@link #offer}.
     * @param diagnostic non-null occurrence, never the configured synthetic marker
     */
    public void add(Diagnostic diagnostic) {
        offer(diagnostic);
    }

    /**
     * Adds one diagnostic and reports whether this occurrence was retained.
     *
     * <p>A caller may use the result to apply the same high-cardinality budget
     * to an external delivery sink. A first error or fatal occurrence that
     * displaces a lower-severity item is reported as retained.</p>
     * @param diagnostic non-null occurrence
     * @return whether this occurrence is retained now
     * @throws IllegalArgumentException if its code equals the configured marker
     * @throws ArithmeticException if the total observed count exceeds long range
     */
    public boolean offer(Diagnostic diagnostic) {
        requireOccurrence(diagnostic);
        observed = Math.incrementExact(observed);
        if (diagnostic.code().impact() == DiagnosticImpact.OPERATION) {
            retained.add(diagnostic);
            trackRetained(diagnostic);
            return true;
        }
        if (budgetedRetained < limit) {
            retained.add(diagnostic);
            budgetedRetained++;
            trackRetained(diagnostic);
            return true;
        }
        boolean firstError = diagnostic.severity() == DiagnosticSeverity.ERROR && !hasErrorOrWorse;
        boolean firstFatal = diagnostic.severity() == DiagnosticSeverity.FATAL && !hasFatal;
        if (firstError || firstFatal) {
            int replacement = lowestSeverityIndex();
            Diagnostic displaced = retained.set(replacement, diagnostic);
            trackRetained(diagnostic);
            suppress(displaced);
            return true;
        }
        suppress(diagnostic);
        return false;
    }

    /**
     * Creates a display snapshot. When suppression occurred, appends one marker
     * with integer limit, long suppressedCount and immutable suppressedBySeverity
     * keyed by severity names. Its severity is the highest omitted severity.
     * Each call may assign a new marker timestamp; it changes no counts and delivers nothing.
     * @return immutable sample in current list order, followed by the optional synthetic marker
     */
    public List<Diagnostic> diagnostics() {
        if (suppressed == 0) {
            return List.copyOf(retained);
        }
        var result = new ArrayList<>(retained);
        result.add(factory.create(suppressionCode)
                .severity(highestSuppressedSeverity())
                .with("limit", limit)
                .with("suppressedCount", suppressed)
                .with("suppressedBySeverity", stringCounts())
                .build());
        return List.copyOf(result);
    }

    /** Snapshots counts without constructing a marker.
     * @return exact observed counts, excluding the synthetic display marker */
    public DiagnosticSummary summary() {
        return DiagnosticSummary.of(retained, suppressedBySeverity);
    }

    /** Snapshots the accumulator for a later merge, preserving current sample order.
     * @return immutable retained detail and exact counts, suitable for merging */
    public DiagnosticBatch batch() {
        return DiagnosticBatch.of(retained, summary(), suppressionCode);
    }

    private void requireOccurrence(Diagnostic diagnostic) {
        Objects.requireNonNull(diagnostic, "diagnostic");
        if (diagnostic.code().equals(suppressionCode)) {
            throw new IllegalArgumentException("Synthetic suppression markers are not occurrences");
        }
    }

    private void suppress(Diagnostic diagnostic) {
        suppressed = Math.incrementExact(suppressed);
        suppressedBySeverity.merge(diagnostic.severity(), 1L, Math::addExact);
    }

    private void trackRetained(Diagnostic diagnostic) {
        hasErrorOrWorse |= diagnostic.severity().isErrorOrWorse();
        hasFatal |= diagnostic.severity() == DiagnosticSeverity.FATAL;
    }

    private int lowestSeverityIndex() {
        int selected = -1;
        for (int index = 0; index < retained.size(); index++) {
            var candidate = retained.get(index);
            if (candidate.code().impact() == DiagnosticImpact.OPERATION) {
                continue;
            }
            if (selected < 0
                    || candidate.severity().compareTo(retained.get(selected).severity()) < 0) {
                selected = index;
            }
        }
        if (selected < 0) {
            throw new IllegalStateException("budget exhausted without a budgeted diagnostic");
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
