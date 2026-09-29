package com.iocextractor.bootstrap;

import com.iocextractor.adapter.processing.camel.runtime.CamelRouteRuntime;
import com.iocextractor.adapter.out.sink.csv.CsvArtifactPreparer;
import com.iocextractor.application.artifact.RoutedArtifactCandidate;
import com.iocextractor.application.pipeline.payload.IndicatorOccurrence;
import com.iocextractor.application.port.out.artifact.DocumentProcessingPlan;
import com.iocextractor.diagnostics.result.Result;
import com.iocextractor.processing.classification.IndicatorClassifier;
import com.iocextractor.processing.model.ClassifiedIndicator;
import java.time.Clock;
import java.util.List;
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
        this(plan, runtime, classifier, clock, Map.of());
    }

    DocumentProcessingAdapter(ProcessingPlanCatalog.CompiledPlan plan,
                              CamelRouteRuntime runtime, IndicatorClassifier classifier,
                              Clock clock, Map<String, CsvArtifactPreparer> preparers) {
        this.classifier = Objects.requireNonNull(classifier, "classifier");
        this.route = new IocProcessingRouteAdapter(plan, runtime, clock);
        this.preparers = Map.copyOf(preparers);
    }

    @Override
    public Result<List<RoutedArtifactCandidate>> prepare(IndicatorOccurrence occurrence) {
        var original = new ProcessingView(new ClassifiedIndicator(occurrence.indicator(),
                classifier.classify(occurrence.indicator())),
                occurrence.orderingPosition(), occurrence.tieOrdinal(), preparers);
        return route.prepare(original);
    }
}
