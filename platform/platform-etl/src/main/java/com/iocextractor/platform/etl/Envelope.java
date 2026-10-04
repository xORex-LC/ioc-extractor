package com.iocextractor.platform.etl;

import com.iocextractor.diagnostics.Diagnostic;
import com.iocextractor.diagnostics.result.DiagnosticBatch;
import com.iocextractor.diagnostics.result.DiagnosticSummary;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * Immutable message envelope passed between pipeline stages.
 *
 * @param payload stage payload
 * @param meta pipeline metadata
 * @param diagnostics accumulated diagnostics
 * @param diagnosticSummary exact observed counts, including omitted detail
 * @param <T> payload type
 */
public record Envelope<T>(T payload, EnvelopeMeta meta, List<Diagnostic> diagnostics,
                          DiagnosticSummary diagnosticSummary) {

    /** Creates an envelope whose diagnostic detail is complete. */
    public Envelope(T payload, EnvelopeMeta meta, List<Diagnostic> diagnostics) {
        this(payload, meta, diagnostics, DiagnosticSummary.empty().plusDiagnostics(diagnostics));
    }

    /**
     * Creates an envelope with defensive diagnostics copying.
     */
    public Envelope {
        Objects.requireNonNull(meta, "meta");
        diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
        Objects.requireNonNull(diagnosticSummary, "diagnosticSummary");
    }

    /**
     * Creates an envelope without diagnostics.
     *
     * @param payload payload
     * @param meta metadata
     * @param <T> payload type
     * @return envelope
     */
    public static <T> Envelope<T> of(T payload, EnvelopeMeta meta) {
        return new Envelope<>(payload, meta, List.of());
    }

    /**
     * Returns a copy with a different payload.
     *
     * @param nextPayload new payload
     * @param <R> new payload type
     * @return envelope with the new payload
     */
    public <R> Envelope<R> withPayload(R nextPayload) {
        return new Envelope<>(nextPayload, meta, diagnostics, diagnosticSummary);
    }

    /**
     * Returns a copy whose metadata points at the supplied stage.
     *
     * @param stage current stage
     * @return envelope at the supplied stage
     */
    public Envelope<T> atStage(StageId stage) {
        return new Envelope<>(payload, meta.atStage(stage), diagnostics, diagnosticSummary);
    }

    /**
     * Returns a copy with one additional diagnostic.
     *
     * @param diagnostic diagnostic to append
     * @return envelope with appended diagnostic
     */
    public Envelope<T> withDiagnostic(Diagnostic diagnostic) {
        Objects.requireNonNull(diagnostic, "diagnostic");
        var next = new ArrayList<>(diagnostics);
        next.add(diagnostic);
        return new Envelope<>(payload, meta, next, diagnosticSummary.plusDiagnostics(List.of(diagnostic)));
    }

    /**
     * Returns a copy with additional diagnostics.
     *
     * @param additional diagnostics to append
     * @return envelope with appended diagnostics
     */
    public Envelope<T> withDiagnostics(Collection<Diagnostic> additional) {
        var next = new ArrayList<>(diagnostics);
        next.addAll(Objects.requireNonNull(additional, "additional"));
        return new Envelope<>(payload, meta, next, diagnosticSummary.plusDiagnostics(additional));
    }

    /** Appends an independently bounded stage batch while retaining its exact counts. */
    public Envelope<T> withDiagnostics(DiagnosticBatch additional) {
        Objects.requireNonNull(additional, "additional");
        var next = new ArrayList<>(diagnostics);
        next.addAll(additional.retained());
        return new Envelope<>(payload, meta, next, diagnosticSummary.plus(additional.summary()));
    }

    /**
     * Returns a copy with one additional metadata attribute.
     *
     * @param key attribute key
     * @param value attribute value
     * @return envelope with updated metadata
     */
    public Envelope<T> withMetaAttribute(String key, Object value) {
        return new Envelope<>(payload, meta.withAttribute(key, value), diagnostics, diagnosticSummary);
    }
}
