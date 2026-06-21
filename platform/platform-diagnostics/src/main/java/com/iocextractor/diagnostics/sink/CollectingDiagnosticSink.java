package com.iocextractor.diagnostics.sink;

import com.iocextractor.diagnostics.Diagnostic;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Diagnostic sink that stores emitted diagnostics in memory.
 *
 * <p>This sink is intended for tests and report assembly. It is not
 * thread-safe.
 */
public final class CollectingDiagnosticSink implements DiagnosticSink {

    private final List<Diagnostic> diagnostics = new ArrayList<>();

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
