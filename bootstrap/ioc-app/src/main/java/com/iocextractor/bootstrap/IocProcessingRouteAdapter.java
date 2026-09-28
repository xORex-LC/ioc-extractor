package com.iocextractor.bootstrap;

import com.iocextractor.adapter.processing.camel.contract.BranchOutcome;
import com.iocextractor.adapter.processing.camel.contract.FailureReference;
import com.iocextractor.adapter.processing.camel.contract.PlanExecutionResult;
import com.iocextractor.adapter.processing.camel.contract.PlanSelection;
import com.iocextractor.adapter.processing.camel.runtime.CamelRouteRuntime;
import com.iocextractor.application.artifact.RoutedArtifactCandidate;
import com.iocextractor.diagnostics.Diagnostic;
import com.iocextractor.diagnostics.DiagnosticFactory;
import com.iocextractor.diagnostics.DiagnosticSeverity;
import com.iocextractor.diagnostics.codes.PipelineDiagnosticCodes;
import com.iocextractor.diagnostics.result.Result;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Interprets technical Router outcomes as application candidates and diagnostics. */
final class IocProcessingRouteAdapter {
    private final ProcessingPlanCatalog.CompiledPlan plan;
    private final DiagnosticFactory diagnostics;
    private final CamelRouteRuntime runtime;

    IocProcessingRouteAdapter(ProcessingPlanCatalog.CompiledPlan plan,
                             CamelRouteRuntime runtime, Clock clock) {
        this.plan = Objects.requireNonNull(plan, "plan");
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.diagnostics = new DiagnosticFactory(clock);
    }

    /** Executes one parsed IOC view and resolves final demanded-consumer diagnostics. */
    Result<List<RoutedArtifactCandidate>> prepare(ProcessingView original) {
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
                    produced.add(viewFailure(original, reply.branchId(), unavailable.failure(), false));
                }
            }
        }
        for (var failure : result.failures()) {
            produced.add(viewFailure(original, failure.viewId(), failure.failure(),
                    failure.unrecoveredConsumers().isEmpty()));
        }
        PlanSelection.Status status = result.selection().status();
        if (status == PlanSelection.Status.REJECTED || status == PlanSelection.Status.AMBIGUOUS) {
            produced.add(diagnostics.create(PipelineDiagnosticCodes.ROUTING_REJECTED)
                    .with("plan", plan.router().id())
                    .with("indicator", original.classified().indicator().value())
                    .with("reason", status.name()).build());
        }
        return Result.of(candidates, produced);
    }

    private Diagnostic viewFailure(ProcessingView original, String view,
                                   FailureReference failure, boolean recovered) {
        return diagnostics.create(PipelineDiagnosticCodes.VIEW_UNAVAILABLE)
                .severity(recovered ? DiagnosticSeverity.WARN : DiagnosticSeverity.ERROR)
                .with("view", view)
                .with("indicator", original.classified().indicator().value())
                .with("reason", failure.reasonCode())
                .with("plan", plan.router().id())
                .with("producer", failure.producerId())
                .build();
    }
}
