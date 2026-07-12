package com.iocextractor.application.port.in;

import com.iocextractor.application.pipeline.CompletionStatus;
import com.iocextractor.diagnostics.Diagnostic;
import com.iocextractor.diagnostics.result.DiagnosticSummary;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Outcome summary of an extraction run.
 *
 * @param extracted        indicators detected (after overlap resolution)
 * @param retained         indicators kept after de-duplication
 * @param writtenPerArtifact rows written, keyed by artifact name
 */
public record ExtractionResult(int extracted,
                               int retained,
                               Map<String, Integer> writtenPerArtifact,
                               CompletionStatus completionStatus,
                               List<Diagnostic> diagnostics,
                               DiagnosticSummary diagnosticSummary) {

    public ExtractionResult {
        writtenPerArtifact = Map.copyOf(Objects.requireNonNull(writtenPerArtifact, "writtenPerArtifact"));
        Objects.requireNonNull(completionStatus, "completionStatus");
        diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
        Objects.requireNonNull(diagnosticSummary, "diagnosticSummary");
    }

    public ExtractionResult(int extracted, int retained, Map<String, Integer> writtenPerArtifact) {
        this(extracted, retained, writtenPerArtifact, CompletionStatus.COMPLETED,
                List.of(), DiagnosticSummary.empty());
    }

}
