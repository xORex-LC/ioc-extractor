package com.iocextractor.adapter.out.sink.csv;

import com.iocextractor.application.artifact.ArtifactIdSequence;
import com.iocextractor.application.artifact.ArtifactRow;
import com.iocextractor.application.artifact.ArtifactWritePlan;
import com.iocextractor.application.artifact.PreparedArtifactRow;
import com.iocextractor.application.pipeline.payload.ClassifiedIndicator;
import com.iocextractor.application.port.out.artifact.ArtifactPreparer;
import com.iocextractor.diagnostics.Diagnostic;
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

    /**
     * Creates a preparer for one configured artifact.
     *
     * @param definition artifact routing and row-mapping definition
     * @param ids deferred public-id sequence
     * @param diagnosticFactory factory for element mapping diagnostics
     * @param sourceKey stable ingestion source key, or {@code null} in oneshot mode
     */
    public CsvArtifactPreparer(CsvArtifactDefinition definition,
                               ArtifactIdSequence ids,
                               DiagnosticFactory diagnosticFactory,
                               String sourceKey) {
        this.definition = Objects.requireNonNull(definition, "definition");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.diagnosticFactory = Objects.requireNonNull(diagnosticFactory, "diagnosticFactory");
        this.sourceKey = sourceKey;
    }

    @Override
    public String name() {
        return definition.name();
    }

    @Override
    public Result<ArtifactWritePlan> prepare(List<ClassifiedIndicator> indicators) {
        var rows = new ArrayList<PreparedArtifactRow>();
        var diagnostics = new ArrayList<Diagnostic>();
        int ordinal = 0;
        for (ClassifiedIndicator classified : indicators) {
            if (!definition.accepts().contains(classified.indicator().type())
                    || !definition.filter().accepts(classified)) {
                ordinal++;
                continue;
            }
            try {
                rows.add(prepareRow(classified));
            } catch (RowMappingException failure) {
                diagnostics.add(diagnosticFactory.create(SinkDiagnosticCodes.ROW_MAPPING_FAILED)
                        .with("sink", definition.name())
                        .with("indicator", "#" + ordinal + ":" + classified.indicator().type())
                        .with("reason", reason(failure))
                        .cause(failure)
                        .build());
            }
            ordinal++;
        }
        var plan = new ArtifactWritePlan(
                definition.name(), definition.mapper().header(), rows, ids);
        return Result.of(plan, diagnostics);
    }

    private PreparedArtifactRow prepareRow(ClassifiedIndicator classified) {
        List<String> values = definition.mapper().toRow(0, classified);
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
