package com.iocextractor.adapter.out.sink.csv;

import com.iocextractor.application.artifact.ArtifactIdSequence;
import com.iocextractor.application.artifact.ArtifactRow;
import com.iocextractor.application.artifact.ArtifactWritePlan;
import com.iocextractor.application.artifact.PreparedArtifactRow;
import com.iocextractor.application.pipeline.payload.ClassifiedIndicator;
import com.iocextractor.application.observability.PipelineDecisionKind;
import com.iocextractor.application.observability.PipelineItemDecision;
import com.iocextractor.application.port.out.artifact.ArtifactPreparer;
import com.iocextractor.application.port.out.observability.PipelineDecisionTracer;
import com.iocextractor.diagnostics.Diagnostic;
import com.iocextractor.diagnostics.DiagnosticContextKeys;
import com.iocextractor.diagnostics.DiagnosticFactory;
import com.iocextractor.diagnostics.codes.SinkDiagnosticCodes;
import com.iocextractor.diagnostics.result.Result;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;

/** CSV-configured, side-effect-free implementation of artifact row preparation. */
public final class CsvArtifactPreparer implements ArtifactPreparer {

    private final CsvArtifactDefinition definition;
    private final ArtifactIdSequence ids;
    private final DiagnosticFactory diagnosticFactory;
    private final String sourceKey;
    private final PipelineDecisionTracer tracer;

    /**
     * Creates a preparer for one configured artifact.
     *
     * @param definition artifact routing and row-mapping definition
     * @param ids deferred public-id sequence
     * @param diagnosticFactory factory for element mapping diagnostics
     * @param sourceKey stable ingestion source key, or {@code null} in oneshot mode
     * @param tracer gated operational decision boundary
     */
    public CsvArtifactPreparer(CsvArtifactDefinition definition,
                               ArtifactIdSequence ids,
                               DiagnosticFactory diagnosticFactory,
                               String sourceKey,
                               PipelineDecisionTracer tracer) {
        this.definition = Objects.requireNonNull(definition, "definition");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.diagnosticFactory = Objects.requireNonNull(diagnosticFactory, "diagnosticFactory");
        this.sourceKey = sourceKey;
        this.tracer = Objects.requireNonNull(tracer, "tracer");
    }

    @Override
    public String name() {
        return definition.name();
    }

    @Override
    public Result<ArtifactWritePlan> prepare(List<ClassifiedIndicator> indicators) {
        var rows = new ArrayList<PreparedArtifactRow>();
        var diagnostics = new ArrayList<Diagnostic>();
        for (int ordinal = 0; ordinal < indicators.size(); ordinal++) {
            ClassifiedIndicator classified = indicators.get(ordinal);
            boolean accepted = definition.accepts().contains(classified.indicator().type())
                    && definition.filter().accepts(classified);
            if (!accepted) {
                trace(classified, "filtered");
                continue;
            }
            try {
                rows.add(prepareRow(classified));
                trace(classified, "routed");
            } catch (RowMappingException failure) {
                trace(classified, "mapping_failed");
                diagnostics.add(diagnosticFactory.create(SinkDiagnosticCodes.ROW_MAPPING_FAILED)
                        .with("sink", definition.name())
                        .with(DiagnosticContextKeys.ARTIFACT, definition.name())
                        .with(DiagnosticContextKeys.COLUMN, failure.column())
                        .with(DiagnosticContextKeys.COMPONENT_KIND, failure.componentKind().value())
                        .with(DiagnosticContextKeys.COMPONENT_NAME, failure.componentName())
                        .with(DiagnosticContextKeys.INDICATOR, classified.indicator().value())
                        .with(DiagnosticContextKeys.TYPE, classified.indicator().type())
                        .with(DiagnosticContextKeys.SOURCE, sourceKey(classified))
                        .with(DiagnosticContextKeys.ORDINAL, ordinal)
                        .with("reason", reason(failure))
                        .build());
            }
        }
        var plan = new ArtifactWritePlan(
                definition.name(), definition.mapper().header(), rows, ids);
        return Result.of(plan, diagnostics);
    }

    private void trace(ClassifiedIndicator classified, String outcome) {
        if (!tracer.isEnabled()) {
            return;
        }
        tracer.trace(PipelineItemDecision.builder(PipelineDecisionKind.ROUTING, outcome)
                .item(classified.indicator().type().name(), classified.indicator().value())
                .artifact(definition.name())
                .build());
    }

    private PreparedArtifactRow prepareRow(ClassifiedIndicator classified) {
        List<String> values = definition.mapper().toRow(classified);
        var row = new LinkedHashMap<String, String>();
        List<String> header = definition.mapper().header();
        for (int index = 0; index < header.size(); index++) {
            row.put(header.get(index), index < values.size() ? values.get(index) : null);
        }
        row.put("_source_key", sourceKey(classified));
        return new PreparedArtifactRow(
                ArtifactRow.ordered(row), definition.mapper().idColumn());
    }

    private String sourceKey(ClassifiedIndicator classified) {
        if (sourceKey != null && !sourceKey.isBlank()) {
            return sourceKey;
        }
        String label = classified.indicator().source().label();
        return label == null || label.isBlank() ? "oneshot" : label;
    }

    private static String reason(RuntimeException failure) {
        return failure.getMessage() == null || failure.getMessage().isBlank()
                ? failure.getClass().getSimpleName()
                : failure.getMessage();
    }
}
