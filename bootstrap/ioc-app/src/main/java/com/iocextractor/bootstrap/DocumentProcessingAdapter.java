package com.iocextractor.bootstrap;

import com.iocextractor.adapter.processing.camel.runtime.CamelRouteRuntime;
import com.iocextractor.application.artifact.RoutedArtifactCandidate;
import com.iocextractor.application.pipeline.payload.IndicatorOccurrence;
import com.iocextractor.application.port.out.artifact.DocumentProcessingPlan;
import com.iocextractor.diagnostics.result.Result;
import com.iocextractor.processing.classification.IndicatorClassifier;
import com.iocextractor.processing.model.ClassifiedIndicator;
import java.time.Clock;
import java.util.List;
import java.util.Objects;

/** Binds attributed document occurrences to the shared IOC route adapter. */
final class DocumentProcessingAdapter implements DocumentProcessingPlan {
    private final IndicatorClassifier classifier;
    private final IocProcessingRouteAdapter route;

    DocumentProcessingAdapter(ProcessingPlanCatalog.CompiledPlan plan,
                              CamelRouteRuntime runtime, IndicatorClassifier classifier,
                              Clock clock) {
        this.classifier = Objects.requireNonNull(classifier, "classifier");
        this.route = new IocProcessingRouteAdapter(plan, runtime, clock);
    }

    @Override
    public Result<List<RoutedArtifactCandidate>> prepare(IndicatorOccurrence occurrence) {
        var original = new ProcessingView(new ClassifiedIndicator(occurrence.indicator(),
                classifier.classify(occurrence.indicator())),
                occurrence.orderingPosition(), occurrence.tieOrdinal());
        return route.prepare(original);
    }
}
