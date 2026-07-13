package com.iocextractor.application.pipeline.stage;

import com.iocextractor.application.pipeline.payload.AttributedIndicators;
import com.iocextractor.application.pipeline.payload.DeduplicationDecision;
import com.iocextractor.application.pipeline.payload.DeduplicatedIndicators;
import com.iocextractor.diagnostics.Diagnostic;
import com.iocextractor.diagnostics.DiagnosticContextKeys;
import com.iocextractor.diagnostics.DiagnosticFactory;
import com.iocextractor.diagnostics.codes.PipelineDiagnosticCodes;
import com.iocextractor.domain.model.Indicator;
import com.iocextractor.platform.etl.Envelope;
import com.iocextractor.platform.etl.Stage;
import com.iocextractor.platform.etl.StageId;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Removes within-batch duplicates. Storage-level keep-first semantics and
 * provenance accounting belong to the canonical artifact repository.
 */
public final class DeduplicateIndicatorsStage implements Stage<AttributedIndicators, DeduplicatedIndicators> {

    private final boolean deduplicate;
    private final DiagnosticFactory diagnosticFactory;

    /**
     * Creates the stage.
     *
     * @param deduplicate whether within-batch de-duplication is enabled
     * @param diagnosticFactory factory for duplicate-drop diagnostics
     */
    public DeduplicateIndicatorsStage(boolean deduplicate, DiagnosticFactory diagnosticFactory) {
        this.deduplicate = deduplicate;
        this.diagnosticFactory = Objects.requireNonNull(diagnosticFactory, "diagnosticFactory");
    }

    @Override
    public StageId name() {
        return StageNames.DEDUPLICATE;
    }

    @Override
    public Envelope<DeduplicatedIndicators> process(Envelope<AttributedIndicators> input) {
        var extracted = input.payload().indicators();
        var result = deduplicate(extracted);
        return input.withPayload(new DeduplicatedIndicators(extracted.size(), result.retained(), result.decisions()))
                .withDiagnostics(result.diagnostics());
    }

    private DeduplicationResult deduplicate(List<Indicator> indicators) {
        Set<String> seen = new HashSet<>();
        List<Indicator> retained = new ArrayList<>(indicators.size());
        List<DeduplicationDecision> decisions = new ArrayList<>(indicators.size());
        List<Diagnostic> diagnostics = new ArrayList<>();
        for (Indicator indicator : indicators) {
            boolean keep = !deduplicate || seen.add(indicator.dedupKey());
            decisions.add(new DeduplicationDecision(indicator, keep));
            if (!keep) {
                diagnostics.add(diagnosticFactory.create(PipelineDiagnosticCodes.ITEM_SKIPPED)
                        .with(DiagnosticContextKeys.ITEM, indicator.value())
                        .with(DiagnosticContextKeys.TYPE, indicator.type())
                        .with("stage", name().value())
                        .with("reason", "duplicate within source batch")
                        .build());
                continue;
            }
            retained.add(indicator);
        }
        return new DeduplicationResult(retained, decisions, diagnostics);
    }

    private record DeduplicationResult(List<Indicator> retained,
                                       List<DeduplicationDecision> decisions,
                                       List<Diagnostic> diagnostics) {
    }
}
