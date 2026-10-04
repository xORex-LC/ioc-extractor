package com.iocextractor.application.pipeline.stage;

import com.iocextractor.application.port.out.artifact.DocumentPreparationWorkspace;
import com.iocextractor.application.pipeline.PipelineMetaAttributes;
import com.iocextractor.application.artifact.DocumentPreparationSummary;
import com.iocextractor.application.artifact.ArtifactWritePlan;
import com.iocextractor.application.artifact.DocumentObservationSelection;
import com.iocextractor.application.pipeline.payload.AttributedIndicators;
import com.iocextractor.application.pipeline.payload.IndicatorOccurrence;
import com.iocextractor.application.pipeline.payload.PreparedArtifacts;
import com.iocextractor.application.port.out.artifact.ArtifactPreparer;
import com.iocextractor.application.port.out.artifact.DocumentProcessingPlan;
import com.iocextractor.diagnostics.DiagnosticFactory;
import com.iocextractor.diagnostics.codes.PipelineDiagnosticCodes;
import com.iocextractor.diagnostics.result.BoundedDiagnosticCollector;
import com.iocextractor.platform.etl.Envelope;
import com.iocextractor.platform.etl.Stage;
import com.iocextractor.platform.etl.StageId;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Applies the required document plan and its observation policy before the write checkpoint. */
public final class PrepareRoutedArtifactsStage implements Stage<AttributedIndicators, PreparedArtifacts> {
    private final DocumentProcessingPlan processing;
    private final List<ArtifactPreparer> preparers;
    private final boolean deduplicate;
    private final DiagnosticFactory diagnostics;
    private final int diagnosticLimit;

    public PrepareRoutedArtifactsStage(DocumentProcessingPlan processing,
                                       List<ArtifactPreparer> preparers,
                                       boolean deduplicate) {
        this(processing, preparers, deduplicate,
                new DiagnosticFactory(Clock.systemUTC()));
    }

    public PrepareRoutedArtifactsStage(DocumentProcessingPlan processing,
                                       List<ArtifactPreparer> preparers,
                                       boolean deduplicate, DiagnosticFactory diagnostics) {
        this(processing, preparers, deduplicate, diagnostics, 10_000);
    }

    /** Creates the stage with the same retained-occurrence budget as its runner. */
    public PrepareRoutedArtifactsStage(DocumentProcessingPlan processing,
                                       List<ArtifactPreparer> preparers,
                                       boolean deduplicate, DiagnosticFactory diagnostics, int diagnosticLimit) {
        if (diagnosticLimit < 1) {
            throw new IllegalArgumentException("diagnosticLimit must be positive");
        }
        this.diagnosticLimit = diagnosticLimit;
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
        this.processing = Objects.requireNonNull(processing, "processing");
        this.preparers = List.copyOf(preparers);
        this.deduplicate = deduplicate;
    }

    @Override
    public StageId name() {
        return StageNames.PREPARE_ARTIFACTS;
    }

    @Override
    public Envelope<PreparedArtifacts> process(Envelope<AttributedIndicators> input) {
        var workspace = (DocumentPreparationWorkspace)
                Objects.requireNonNull(input.meta().attributes().get(PipelineMetaAttributes.DOCUMENT_PREPARATION_WORKSPACE),
                        "document preparation workspace");
        Map<String, ArtifactWritePlan> emptyPlans = emptyPlans();
        var diagnostics = new BoundedDiagnosticCollector(diagnosticLimit);
        boolean retainObservations = processing.observationSelection()
                == DocumentObservationSelection.RETAINED_OBSERVATIONS;
        int retained = 0;
        int ordinal = 0;
        try (var session = processing.openSession()) {
            for (var decision : input.payload().outcome().decisions()) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new IllegalStateException("Document preparation interrupted");
                }
                var indicator = decision.indicator();
                boolean keep = !deduplicate || workspace.firstOriginal(indicator.dedupKey());
                var occurrence = new IndicatorOccurrence(indicator, decision.rawIndicator().position(),
                        ordinal++, !retainObservations || keep);
                if (keep) {
                    retained++;
                } else if (retainObservations) {
                    diagnostics.add(this.diagnostics.create(PipelineDiagnosticCodes.ITEM_SKIPPED)
                            .with("item", indicator.value()).with("type", indicator.type())
                            .with("stage", StageNames.DEDUPLICATE.value())
                            .with("reason", "duplicate within source batch").build());
                }
                var result = session.prepare(occurrence);
                diagnostics.addAll(result.diagnostics());
                for (var candidate : Objects.requireNonNull(result.value(), "routed candidates")) {
                    if (!emptyPlans.containsKey(candidate.artifact())) {
                        throw new IllegalStateException("Routed candidate targets unknown artifact: " + candidate.artifact());
                    }
                    workspace.append(candidate, keep, retainObservations);
                }
            }
        }
        var plans = workspace.seal(List.copyOf(emptyPlans.values()),
                new DocumentPreparationSummary(ordinal, retained, diagnostics.batch().summary()));
        return input.withPayload(new PreparedArtifacts(ordinal, retained, plans))
                .withDiagnostics(diagnostics.batch());
    }

    private Map<String, ArtifactWritePlan> emptyPlans() {
        Map<String, ArtifactWritePlan> emptyPlans = new LinkedHashMap<>();
        for (ArtifactPreparer preparer : preparers) {
            ArtifactWritePlan empty = preparer.prepare(List.of()).value();
            if (empty == null || emptyPlans.putIfAbsent(preparer.name(), empty) != null) {
                throw new IllegalStateException("Duplicate or missing artifact preparation: " + preparer.name());
            }
        }
        return emptyPlans;
    }
}
