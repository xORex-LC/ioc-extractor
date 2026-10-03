package com.iocextractor.bootstrap;

import com.iocextractor.adapter.processing.camel.runtime.CamelRouteRuntime;
import com.iocextractor.adapter.out.sink.csv.CsvArtifactPreparer;
import com.iocextractor.application.artifact.RoutedArtifactCandidate;
import com.iocextractor.application.pipeline.payload.IndicatorOccurrence;
import com.iocextractor.application.port.out.artifact.DocumentProcessingPlan;
import com.iocextractor.application.port.out.artifact.DocumentProcessingSession;
import com.iocextractor.processing.session.IndicatorProcessingSession;
import com.iocextractor.application.port.out.artifact.ArtifactPreparer;
import com.iocextractor.diagnostics.result.Result;
import com.iocextractor.processing.classification.IndicatorClassifier;
import com.iocextractor.processing.model.ClassifiedIndicator;
import java.time.Clock;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Binds attributed document occurrences to the shared IOC route adapter. */
final class DocumentProcessingAdapter implements DocumentProcessingPlan {
    private final IndicatorClassifier classifier;
    private final IocProcessingRouteAdapter route;
    private final Map<String, CsvArtifactPreparer> preparers;

    DocumentProcessingAdapter(ProcessingPlanCatalog.CompiledPlan plan,
                              CamelRouteRuntime runtime, IndicatorClassifier classifier,
                              Clock clock) {
        this(plan, runtime, classifier, clock, List.of());
    }

    DocumentProcessingAdapter(ProcessingPlanCatalog.CompiledPlan plan,
                              CamelRouteRuntime runtime, IndicatorClassifier classifier,
                              Clock clock, List<ArtifactPreparer> preparers) {
        this(plan, runtime, classifier, clock, byArtifact(preparers));
    }

    DocumentProcessingAdapter(ProcessingPlanCatalog.CompiledPlan plan,
                              CamelRouteRuntime runtime, IndicatorClassifier classifier,
                              Clock clock, Map<String, CsvArtifactPreparer> preparers) {
        this.classifier = Objects.requireNonNull(classifier, "classifier");
        this.route = new IocProcessingRouteAdapter(plan, runtime, clock);
        this.preparers = Map.copyOf(preparers);
    }

    private static Map<String, CsvArtifactPreparer> byArtifact(List<ArtifactPreparer> preparers) {
        Map<String, CsvArtifactPreparer> result = new LinkedHashMap<>();
        for (ArtifactPreparer preparer : preparers) {
            result.put(preparer.name(), (CsvArtifactPreparer) preparer);
        }
        return result;
    }

    @Override
    public Result<List<RoutedArtifactCandidate>> prepare(IndicatorOccurrence occurrence) {
        try (var session = new IndicatorProcessingSession(classifier, 0, 0)) {
            return prepare(occurrence, session);
        }
    }

    @Override
    public DocumentProcessingSession openSession() {
        var semantics = new IndicatorProcessingSession(classifier);
        return new DocumentProcessingSession() {
            @Override
            public Result<List<RoutedArtifactCandidate>> prepare(IndicatorOccurrence occurrence) {
                return DocumentProcessingAdapter.this.prepare(occurrence, semantics);
            }

            @Override
            public void close() {
                semantics.close();
            }
        };
    }

    private Result<List<RoutedArtifactCandidate>> prepare(IndicatorOccurrence occurrence,
                                                         IndicatorProcessingSession session) {
        var original = new ProcessingView(new ClassifiedIndicator(occurrence.indicator(),
                session.classify(occurrence.indicator())),
                occurrence.orderingPosition(), occurrence.tieOrdinal(), preparers, session);
        return route.prepare(original);
    }
}
