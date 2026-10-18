package com.iocextractor.platform.diagnostics.sink;

import com.iocextractor.platform.diagnostics.Diagnostic;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Diagnostic sink that stores emitted diagnostics in memory.
 *
 * <p>This sink is intended for tests and report assembly. It is not
 * thread-safe and has no retention limit. Every emit records one occurrence.
 */
public final class CollectingDiagnosticSink implements DiagnosticSink {

    private final List<Diagnostic> diagnostics = new ArrayList<>();

    /** Creates an empty in-memory sink. */
    public CollectingDiagnosticSink() {
    }

    /** {@inheritDoc} */
    @Override
    public void emit(Diagnostic diagnostic) {
        diagnostics.add(Objects.requireNonNull(diagnostic, "diagnostic"));
    }

    /**
     * Returns emitted diagnostics.
     *
     * @return immutable snapshot
     */
    public List<Diagnostic> diagnostics() {
        return List.copyOf(diagnostics);
    }
}
