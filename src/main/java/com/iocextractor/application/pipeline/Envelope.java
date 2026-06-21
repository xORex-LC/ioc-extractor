package com.iocextractor.application.pipeline;

import com.iocextractor.diagnostics.Diagnostic;

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
 * @param <T> payload type
 */
public record Envelope<T>(T payload, EnvelopeMeta meta, List<Diagnostic> diagnostics) {

    /**
     * Creates an envelope with defensive diagnostics copying.
     */
    public Envelope {
        Objects.requireNonNull(meta, "meta");
        diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
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
        return new Envelope<>(nextPayload, meta, diagnostics);
    }

    /**
     * Returns a copy whose metadata points at the supplied stage.
     *
     * @param stage current stage
     * @return envelope at the supplied stage
     */
    public Envelope<T> atStage(StageName stage) {
        return new Envelope<>(payload, meta.atStage(stage), diagnostics);
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
        return new Envelope<>(payload, meta, next);
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
        return new Envelope<>(payload, meta, next);
    }

    /**
     * Returns a copy with one additional metadata attribute.
     *
     * @param key attribute key
     * @param value attribute value
     * @return envelope with updated metadata
     */
    public Envelope<T> withMetaAttribute(String key, Object value) {
        return new Envelope<>(payload, meta.withAttribute(key, value), diagnostics);
    }
}
