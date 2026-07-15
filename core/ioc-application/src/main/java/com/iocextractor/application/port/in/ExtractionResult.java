package com.iocextractor.application.port.in;

import com.iocextractor.application.pipeline.CompletionStatus;
import com.iocextractor.diagnostics.Diagnostic;
import com.iocextractor.diagnostics.result.DiagnosticSummary;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Outcome summary of an extraction run.
 *
 * @param runId            correlation identifier of this extraction run
 * @param extracted        indicators detected (after overlap resolution)
 * @param retained         indicators kept after de-duplication
 * @param writtenPerArtifact rows written, keyed by artifact name
 * @param completionStatus terminal structural completion
 * @param diagnostics retained diagnostics from the bounded run outcome
 * @param diagnosticSummary complete counts, including suppressed diagnostics
 */
public record ExtractionResult(String runId,
                               int extracted,
                               int retained,
                               Map<String, Integer> writtenPerArtifact,
                               CompletionStatus completionStatus,
                               List<Diagnostic> diagnostics,
                               DiagnosticSummary diagnosticSummary) {

    public ExtractionResult {
        Objects.requireNonNull(runId, "runId");
        if (runId.isBlank()) {
            throw new IllegalArgumentException("runId must not be blank");
        }
        writtenPerArtifact = Map.copyOf(Objects.requireNonNull(writtenPerArtifact, "writtenPerArtifact"));
        Objects.requireNonNull(completionStatus, "completionStatus");
        diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
        Objects.requireNonNull(diagnosticSummary, "diagnosticSummary");
    }

    /** Returns this result augmented with low-cardinality terminal diagnostics. */
    public ExtractionResult withAdditionalDiagnostics(Collection<Diagnostic> additionalDiagnostics) {
        Objects.requireNonNull(additionalDiagnostics, "additionalDiagnostics");
        if (additionalDiagnostics.isEmpty()) {
            return this;
        }
        var combinedDiagnostics = new ArrayList<>(diagnostics);
        combinedDiagnostics.addAll(additionalDiagnostics);
        var combinedSummary = diagnosticSummary.plusDiagnostics(additionalDiagnostics);
        return new ExtractionResult(
                runId,
                extracted,
                retained,
                writtenPerArtifact,
                CompletionStatus.from(combinedSummary),
                combinedDiagnostics,
                combinedSummary);
    }

}
