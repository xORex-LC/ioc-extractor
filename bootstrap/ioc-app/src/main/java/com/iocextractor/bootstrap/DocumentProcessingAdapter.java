package com.iocextractor.bootstrap;

import com.iocextractor.adapter.processing.camel.contract.BranchOutcome;
import com.iocextractor.adapter.processing.camel.contract.FailureReference;
import com.iocextractor.adapter.processing.camel.contract.PlanExecutionResult;
import com.iocextractor.adapter.processing.camel.contract.PlanSelection;
import com.iocextractor.adapter.processing.camel.runtime.CamelRouteRuntime;
import com.iocextractor.application.artifact.RoutedArtifactCandidate;
import com.iocextractor.application.pipeline.payload.IndicatorOccurrence;
import com.iocextractor.application.port.out.artifact.DocumentProcessingPlan;
import com.iocextractor.diagnostics.Diagnostic;
import com.iocextractor.diagnostics.DiagnosticFactory;
import com.iocextractor.diagnostics.DiagnosticSeverity;
import com.iocextractor.diagnostics.codes.PipelineDiagnosticCodes;
import com.iocextractor.diagnostics.result.Result;
import com.iocextractor.processing.classification.IndicatorClassifier;
import com.iocextractor.processing.model.ClassifiedIndicator;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Interprets technical Router outcomes as application candidates and diagnostics. */
final class DocumentProcessingAdapter implements DocumentProcessingPlan {
    private final ProcessingPlanCatalog.CompiledPlan plan;
    private final IndicatorClassifier classifier;
    private final DiagnosticFactory diagnostics;
    private final CamelRouteRuntime runtime;

    DocumentProcessingAdapter(ProcessingPlanCatalog.CompiledPlan plan,
                              CamelRouteRuntime runtime, IndicatorClassifier classifier,
                              Clock clock) {
        this.plan = Objects.requireNonNull(plan, "plan");
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.classifier = Objects.requireNonNull(classifier, "classifier");
        this.diagnostics = new DiagnosticFactory(clock);
    }

    @Override
    public Result<List<RoutedArtifactCandidate>> prepare(IndicatorOccurrence occurrence) {
        var original = new DocumentView(new ClassifiedIndicator(occurrence.indicator(),
                classifier.classify(occurrence.indicator())), occurrence);
        PlanExecutionResult result = runtime.execute(plan.router().id(), original);
        List<RoutedArtifactCandidate> candidates = new ArrayList<>();
        List<Diagnostic> produced = new ArrayList<>();
        for (var reply : result.replies()) {
            if (reply.outcome() instanceof BranchOutcome.Prepared prepared) {
                candidates.add((RoutedArtifactCandidate) prepared.candidate());
            } else if (reply.outcome() instanceof BranchOutcome.Unavailable unavailable) {
                if (unavailable.evidence() instanceof Diagnostic diagnostic) {
                    produced.add(diagnostic);
                } else {
                    produced.add(viewFailure(occurrence, reply.branchId(), unavailable.failure(), false));
                }
            }
        }
        for (var failure : result.failures()) {
            produced.add(viewFailure(occurrence, failure.viewId(), failure.failure(),
                    failure.unrecoveredConsumers().isEmpty()));
        }
        PlanSelection.Status status = result.selection().status();
        if (status == PlanSelection.Status.REJECTED || status == PlanSelection.Status.AMBIGUOUS) {
            produced.add(diagnostics.create(PipelineDiagnosticCodes.ROUTING_REJECTED)
                    .with("plan", plan.router().id())
                    .with("indicator", occurrence.indicator().value())
                    .with("reason", status.name()).build());
        }
        return Result.of(candidates, produced);
    }

    private Diagnostic viewFailure(IndicatorOccurrence occurrence, String view,
                                   FailureReference failure, boolean recovered) {
        return diagnostics.create(PipelineDiagnosticCodes.VIEW_UNAVAILABLE)
                .severity(recovered ? DiagnosticSeverity.WARN : DiagnosticSeverity.ERROR)
                .with("view", view)
                .with("indicator", occurrence.indicator().value())
                .with("reason", failure.reasonCode())
                .with("plan", plan.router().id())
                .with("producer", failure.producerId())
                .build();
    }
}
