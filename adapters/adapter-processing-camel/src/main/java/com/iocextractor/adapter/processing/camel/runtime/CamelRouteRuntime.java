package com.iocextractor.adapter.processing.camel.runtime;

import com.iocextractor.adapter.processing.camel.compile.CompiledRoutes;
import com.iocextractor.adapter.processing.camel.compile.DispatchRequest;
import com.iocextractor.adapter.processing.camel.contract.PlanExecutionResult;
import com.iocextractor.adapter.processing.camel.contract.PlanSelection;
import com.iocextractor.adapter.processing.camel.contract.ViewOutcome;
import java.util.ArrayList;
import java.util.HashMap;
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

    /** Selects and dispatches one observation through an admitted named plan. */
    public PlanExecutionResult execute(String planId, Object original) {
        Objects.requireNonNull(original);
        CompiledRoutes.CompiledPlan plan = plans.get(planId);
        if (plan == null) {
            throw new IllegalArgumentException("Unknown compiled plan: " + planId);
        }
        ViewResolver views = new ViewResolver(original, plan);
        PlanSelection selection = plan.selector().select(views::resolve);
        if (selection.selectedBranches().isEmpty()) {
            return new PlanExecutionResult(selection, List.of());
        }
        List<String> recipients = new ArrayList<>();
        for (String branchId : selection.selectedBranches()) {
            recipients.add(plan.branches().get(branchId));
        }
        var input = new PlanExecutionResult.BranchInput(original, views.snapshot());
        var request = new DispatchRequest(input, recipients);
        DispatchRequest.Replies replies = Objects.requireNonNull(producer.requestBody(
                plan.dispatchUri(), request, DispatchRequest.Replies.class), "dispatch replies");
        return new PlanExecutionResult(selection, replies.values());
    }

    @Override public void close() throws Exception {
        try {
            producer.stop();
        } finally {
            context.stop();
        }
    }

    /** One call's cache; neither Camel routes nor other invocations share it. */
    private final class ViewResolver {
        private final Map<String, ViewOutcome> resolved = new HashMap<>();
        private final CompiledRoutes.CompiledPlan plan;

        private ViewResolver(Object original, CompiledRoutes.CompiledPlan plan) {
            resolved.put("original", new ViewOutcome.Available(original));
            this.plan = plan;
        }

        private ViewOutcome resolve(String viewId) {
            ViewOutcome known = resolved.get(viewId);
            if (known != null) {
                return known;
            }
            CompiledRoutes.ViewRoute route = plan.views().get(viewId);
            ViewOutcome parent = resolve(route.input());
            ViewOutcome outcome = parent instanceof ViewOutcome.Unavailable
                    ? parent : producer.requestBody(route.uri(),
                            ((ViewOutcome.Available) parent).value(), ViewOutcome.class);
            if (outcome == null) {
                throw new IllegalStateException("Operation returned no view outcome: " + viewId);
            }
            resolved.put(viewId, outcome);
            return outcome;
        }

        private Map<String, ViewOutcome> snapshot() {
            return Map.copyOf(resolved);
        }
    }
}
