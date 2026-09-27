package com.iocextractor.adapter.processing.camel.runtime;

import com.iocextractor.adapter.processing.camel.compile.CompiledRoutes;
import com.iocextractor.adapter.processing.camel.compile.DispatchRequest;
import com.iocextractor.adapter.processing.camel.contract.FailureReference;
import com.iocextractor.adapter.processing.camel.contract.PlanExecutionResult;
import com.iocextractor.adapter.processing.camel.contract.PlanSelection;
import com.iocextractor.adapter.processing.camel.contract.ViewOutcome;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.impl.DefaultCamelContext;

/** Owns an isolated embedded Camel context and its local producer. */
public final class CamelRouteRuntime implements AutoCloseable {
    private final DefaultCamelContext context;
    private final ProducerTemplate producer;
    private final Map<String, CompiledRoutes.CompiledPlan> plans;

    public CamelRouteRuntime(CompiledRoutes compiled) throws Exception {
        Objects.requireNonNull(compiled);
        plans = Map.copyOf(compiled.plans());
        context = new DefaultCamelContext();
        try {
            for (var route : compiled.routes()) {
                context.addRoutes(route);
            }
            context.start();
            producer = context.createProducerTemplate();
        } catch (Exception failure) {
            try {
                context.stop();
            } catch (Exception shutdownFailure) {
                failure.addSuppressed(shutdownFailure);
            }
            throw failure;
        }
    }

    /** Selects, resolves demanded views and dispatches one observation. */
    public PlanExecutionResult execute(String planId, Object original) {
        Objects.requireNonNull(original);
        CompiledRoutes.CompiledPlan plan = plans.get(planId);
        if (plan == null) {
            throw new IllegalArgumentException("Unknown compiled plan: " + planId);
        }
        InvocationViews views = new InvocationViews(producer, original, plan);
        PlanSelection selection = plan.selector().select(views::demand);
        List<String> recipients = new ArrayList<>();
        List<PlanSelection.BlockedBranch> preparationBlocked = new ArrayList<>();
        for (String branchId : selection.selectedBranches()) {
            CompiledRoutes.BranchRoute branch = plan.branches().get(branchId);
            FailureReference failure = resolveRequiredViews(views, branchId,
                    branch.requiredViews());
            if (failure == null) {
                recipients.add(branch.uri());
            } else {
                preparationBlocked.add(new PlanSelection.BlockedBranch(branchId, failure));
            }
        }
        if (recipients.isEmpty()) {
            return result(selection, List.of(), preparationBlocked, views);
        }
        var input = new PlanExecutionResult.BranchInput(original, views.snapshot());
        var request = new DispatchRequest(input, recipients);
        DispatchRequest.Replies replies = Objects.requireNonNull(producer.requestBody(
                plan.dispatchUri(), request, DispatchRequest.Replies.class), "dispatch replies");
        return result(selection, replies.values(), preparationBlocked, views);
    }

    private static FailureReference resolveRequiredViews(InvocationViews views, String branchId,
                                                         List<String> requiredViews) {
        for (String viewId : requiredViews) {
            ViewOutcome outcome = views.demand(branchId, viewId, false);
            if (outcome instanceof ViewOutcome.Unavailable unavailable) {
                return unavailable.failure();
            }
        }
        return null;
    }

    private static PlanExecutionResult result(PlanSelection selection,
                                              List<PlanExecutionResult.BranchReply> replies,
                                              List<PlanSelection.BlockedBranch> preparationBlocked,
                                              InvocationViews views) {
        return new PlanExecutionResult(selection, replies, preparationBlocked,
                views.failureResolutions(), views.recoveryAttempts());
    }

    @Override public void close() throws Exception {
        try {
            producer.stop();
        } finally {
            context.stop();
        }
    }
}
