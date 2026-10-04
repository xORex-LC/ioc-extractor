package com.iocextractor.adapter.out.sink.csv;

import com.iocextractor.processing.mapping.RowMappingException;
import com.iocextractor.processing.mapping.ConfigurableRowMapper;

import com.iocextractor.application.artifact.ArtifactIdSequence;
import com.iocextractor.application.artifact.ArtifactRow;
import com.iocextractor.application.artifact.ArtifactWritePlan;
import com.iocextractor.application.artifact.PreparedArtifactRow;
import com.iocextractor.application.artifact.policy.ArtifactWritePolicy;
import com.iocextractor.application.observability.PipelineDecisionKind;
import com.iocextractor.application.observability.PipelineItemDecision;
import com.iocextractor.processing.model.ClassifiedIndicator;
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
import java.util.Map;
import java.util.Optional;
import com.iocextractor.application.observation.OccurrencePosition;

/** CSV-configured, side-effect-free implementation of artifact row preparation. */
public final class CsvArtifactPreparer implements ArtifactPreparer {

    private final CsvArtifactDefinition definition;
    private final ArtifactIdSequence ids;
    private final DiagnosticFactory diagnosticFactory;
    private final String sourceKey;
    private final PipelineDecisionTracer tracer;

    /** Creates a mapper; canonical identity and winner selection belong to the application. */
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

    /** Maps one selected branch using the existing artifact filter and column mapper. */
    public Result<Optional<PreparedArtifactRow>> prepareRouted(
            ClassifiedIndicator defaultView, Map<String, ClassifiedIndicator> columnViews,
            OccurrencePosition position, int ordinal) {
        Objects.requireNonNull(defaultView, "defaultView");
        Objects.requireNonNull(columnViews, "columnViews");
        Objects.requireNonNull(position, "position");
        if (!accepted(defaultView)) {
            trace(defaultView, "filtered");
            return Result.success(Optional.empty());
        }
        try {
            List<String> values = definition.mapper() instanceof ConfigurableRowMapper mapper
                    ? mapper.toRow(defaultView, columnViews)
                    : unmappedRow(defaultView, columnViews);
            PreparedArtifactRow mapped = preparedRow(defaultView, values);
            var positions = new LinkedHashMap<String, OccurrencePosition>();
            definition.writePolicy().fields().keySet()
                    .forEach(field -> positions.put(field, position));
            trace(defaultView, "routed");
            return Result.success(Optional.of(new PreparedArtifactRow(
                    mapped.template(), mapped.idColumn(), positions)));
        } catch (RowMappingException failure) {
            trace(defaultView, "mapping_failed");
            return Result.of(Optional.empty(), List.of(mappingDiagnostic(defaultView, failure, ordinal)));
        }
    }

    private List<String> unmappedRow(ClassifiedIndicator defaultView,
                                     Map<String, ClassifiedIndicator> columnViews) {
        if (!columnViews.isEmpty()) {
            throw new IllegalStateException("Field views require a configurable row mapper: " + name());
        }
        return definition.mapper().toRow(defaultView);
    }

    @Override
    public Result<ArtifactWritePlan> prepare(List<ClassifiedIndicator> indicators) {
        return prepareRows(indicators);
    }

    /** Returns the configured observation and field update policy for this artifact. */
    public ArtifactWritePolicy writePolicy() {
        return definition.writePolicy();
    }

    private Result<ArtifactWritePlan> prepareRows(List<ClassifiedIndicator> indicators) {
        var rows = new ArrayList<PreparedArtifactRow>();
        var diagnostics = new ArrayList<Diagnostic>();
        for (int ordinal = 0; ordinal < indicators.size(); ordinal++) {
            ClassifiedIndicator classified = indicators.get(ordinal);
            if (!accepted(classified)) {
                trace(classified, "filtered");
                continue;
            }
            try {
                rows.add(prepareRow(classified));
                trace(classified, "routed");
            } catch (RowMappingException failure) {
                trace(classified, "mapping_failed");
                diagnostics.add(mappingDiagnostic(classified, failure, ordinal));
            }
        }
        return Result.of(plan(rows), diagnostics);
    }

    private boolean accepted(ClassifiedIndicator classified) {
        return definition.accepts().contains(classified.indicator().type())
                && definition.filter().accepts(classified);
    }

    private ArtifactWritePlan plan(List<PreparedArtifactRow> rows) {
        return new ArtifactWritePlan(definition.name(), definition.mapper().header(), rows, ids);
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
        return preparedRow(classified, definition.mapper().toRow(classified));
    }

    private PreparedArtifactRow preparedRow(ClassifiedIndicator classified, List<String> values) {
        var row = new LinkedHashMap<String, String>();
        List<String> header = definition.mapper().header();
        for (int index = 0; index < header.size(); index++) {
            row.put(header.get(index), index < values.size() ? values.get(index) : null);
        }
        row.put("_source_key", sourceKey(classified));
        return new PreparedArtifactRow(ArtifactRow.ordered(row), definition.mapper().idColumn());
    }

    private Diagnostic mappingDiagnostic(ClassifiedIndicator classified,
                                         RowMappingException failure,
                                         int ordinal) {
        return diagnosticFactory.create(SinkDiagnosticCodes.ROW_MAPPING_FAILED)
                .with("sink", definition.name())
                .with(DiagnosticContextKeys.ARTIFACT, definition.name())
                .with(DiagnosticContextKeys.COLUMN, failure.column())
                .with(DiagnosticContextKeys.COMPONENT_KIND, failure.componentKind().value())
                .with(DiagnosticContextKeys.COMPONENT_NAME, failure.componentName())
                .with(DiagnosticContextKeys.INDICATOR, classified.indicator().value())
                .with(DiagnosticContextKeys.TYPE, classified.indicator().type())
                .with(DiagnosticContextKeys.SOURCE, sourceKey(classified))
                .with(DiagnosticContextKeys.ORDINAL, ordinal)
                .with("reason", failure.getMessage())
                .build();
    }

    private String sourceKey(ClassifiedIndicator classified) {
        if (sourceKey != null && !sourceKey.isBlank()) {
            return sourceKey;
        }
        String label = classified.indicator().source().label();
        return label == null || label.isBlank() ? "oneshot" : label;
    }

}
