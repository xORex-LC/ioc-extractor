package com.iocextractor.platform.etl;

import com.iocextractor.diagnostics.result.DiagnosticSummary;

import java.util.Objects;

/** Immutable pipeline outcome containing both the final envelope and typed diagnostic summary. */
public record PipelineRunResult<T>(Envelope<T> envelope, DiagnosticSummary diagnosticSummary) {

    public PipelineRunResult {
        Objects.requireNonNull(envelope, "envelope");
        Objects.requireNonNull(diagnosticSummary, "diagnosticSummary");
    }
}
