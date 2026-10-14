package com.iocextractor.adapter.processing.camel.runtime;

import com.iocextractor.adapter.processing.camel.compile.CompiledRoutes;
import com.iocextractor.adapter.processing.camel.contract.FailureReference;
import com.iocextractor.adapter.processing.camel.contract.PlanExecutionResult;
import com.iocextractor.adapter.processing.camel.contract.RoutingTraceEvent;
import com.iocextractor.adapter.processing.camel.contract.RoutingTraceSink;
import com.iocextractor.adapter.processing.camel.contract.ViewOutcome;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.Endpoint;

/** Per-call view cache, explicit recovery executor and demanded-consumer ledger. */
final class InvocationViews {
    private final Map<String, ViewState> resolved = new HashMap<>();
    private final Map<FailureOccurrence, Consumers> consumers = new LinkedHashMap<>();
    private final List<PlanExecutionResult.RecoveryAttempt> attempts = new ArrayList<>();
    private final ProducerTemplate producer;
    private final Map<String, Endpoint> endpoints;
    private final CompiledRoutes.CompiledPlan plan;
    private final String planId;
    private final RoutingTraceSink trace;

    InvocationViews(ProducerTemplate producer, Map<String, Endpoint> endpoints,
                    Object original, CompiledRoutes.CompiledPlan plan,
                    String planId, RoutingTraceSink trace) {
        resolved.put("original", new ViewState(new ViewOutcome.Available(original),
                List.of(), List.of()));
        this.producer = producer;
        this.endpoints = endpoints;
        this.plan = plan;
        this.planId = planId;
        this.trace = trace;
    }

    ViewOutcome demand(String branchId, String viewId, boolean acceptsAbsent) {
        ViewState state = resolve(viewId);
        for (FailureOccurrence recovered : state.recovered()) {
            consumers.computeIfAbsent(recovered, ignored -> new Consumers())
                    .recovered.add(branchId);
        }
        if (state.outcome() instanceof ViewOutcome.Absent absent && !acceptsAbsent) {
            FailureReference failure = new FailureReference(absent.originView(),
                    "ABSENT_REQUIRED");
            consumers.computeIfAbsent(new FailureOccurrence(absent.originView(), failure),
                    ignored -> new Consumers()).unrecovered.add(branchId);
            return new ViewOutcome.Unavailable(failure);
        }
        for (FailureOccurrence unavailable : state.unavailable()) {
            consumers.computeIfAbsent(unavailable, ignored -> new Consumers())
                    .unrecovered.add(branchId);
        }
        return state.outcome();
    }

    private ViewState resolve(String viewId) {
        ViewState known = resolved.get(viewId);
        if (known != null) {
            return known;
        }
        CompiledRoutes.ViewRoute route = plan.views().get(viewId);
        ViewState primary = resolve(route.input());
        ViewState result = route.recovery() == null
                ? evaluateOperation(viewId, route, primary)
                : recover(viewId, route, primary);
        resolved.put(viewId, result);
        traceView(viewId, result.outcome());
        return result;
    }

    private ViewState evaluateOperation(String viewId, CompiledRoutes.ViewRoute route,
                                        ViewState primary) {
        if (primary.outcome() instanceof ViewOutcome.Unavailable
                || primary.outcome() instanceof ViewOutcome.Absent) {
            return primary;
        }
        ViewOutcome outcome = producer.requestBody(endpoints.get(route.uri()),
                ((ViewOutcome.Available) primary.outcome()).value(), ViewOutcome.class);
        if (outcome == null) {
            throw new IllegalStateException("Operation returned no view outcome: " + viewId);
        }
        if (outcome instanceof ViewOutcome.Unavailable unavailable) {
            return new ViewState(outcome, primary.recovered(),
                    List.of(new FailureOccurrence(viewId, unavailable.failure())));
        }
        if (outcome instanceof ViewOutcome.Absent) {
            return new ViewState(new ViewOutcome.Absent(viewId), primary.recovered(), List.of());
        }
        return new ViewState(outcome, primary.recovered(), List.of());
    }

    private ViewState recover(String viewId, CompiledRoutes.ViewRoute route, ViewState primary) {
        if (!(primary.outcome() instanceof ViewOutcome.Unavailable unavailable)) {
            return primary;
        }
        String alternateView = route.recovery().alternateView();
        if (!route.recovery().onReasons().contains(unavailable.failure().reasonCode())) {
            return primary;
        }
        ViewState alternate = resolve(alternateView);
        if (alternate.outcome() instanceof ViewOutcome.Unavailable alternateUnavailable) {
            attempts.add(new PlanExecutionResult.RecoveryAttempt(viewId, alternateView,
                    unavailable.failure(), alternateUnavailable.failure(), false));
            traceRecovery(viewId, unavailable.failure(), "blocked");
            return new ViewState(primary.outcome(), primary.recovered(),
                    combine(primary.unavailable(), alternate.unavailable()));
        }
        if (alternate.outcome() instanceof ViewOutcome.Absent absent) {
            FailureReference absence = new FailureReference(absent.originView(), "ABSENT_REQUIRED");
            attempts.add(new PlanExecutionResult.RecoveryAttempt(viewId, alternateView,
                    unavailable.failure(), absence, false));
            traceRecovery(viewId, unavailable.failure(), "blocked");
            return new ViewState(primary.outcome(), primary.recovered(),
                    combine(primary.unavailable(), List.of(
                            new FailureOccurrence(absent.originView(), absence))));
        }
        attempts.add(new PlanExecutionResult.RecoveryAttempt(viewId, alternateView,
                unavailable.failure(), null, true));
        traceRecovery(viewId, unavailable.failure(), "recovered");
        return new ViewState(alternate.outcome(),
                combine(primary.unavailable(), alternate.recovered()), List.of());
    }

    Map<String, ViewOutcome> snapshot() {
        Map<String, ViewOutcome> outcomes = new HashMap<>();
        resolved.forEach((id, state) -> outcomes.put(id, state.outcome()));
        return Map.copyOf(outcomes);
    }

    List<PlanExecutionResult.FailureResolution> failureResolutions() {
        List<PlanExecutionResult.FailureResolution> evidence = new ArrayList<>();
        consumers.forEach((occurrence, usage) -> evidence.add(
                new PlanExecutionResult.FailureResolution(occurrence.viewId(),
                        occurrence.failure(), List.copyOf(usage.recovered),
                        List.copyOf(usage.unrecovered))));
        return List.copyOf(evidence);
    }

    List<PlanExecutionResult.RecoveryAttempt> recoveryAttempts() {
        return List.copyOf(attempts);
    }

    private void traceView(String viewId, ViewOutcome outcome) {
        String status = outcome instanceof ViewOutcome.Available ? "available"
                : outcome instanceof ViewOutcome.Absent ? "absent" : "unavailable";
        String reason = outcome instanceof ViewOutcome.Unavailable unavailable
                ? unavailable.failure().reasonCode() : null;
        trace.emit(RoutingTraceEvent.Kind.VIEW, planId, viewId, null, null, status, reason);
    }

    private void traceRecovery(String viewId, FailureReference primary, String outcome) {
        trace.emit(RoutingTraceEvent.Kind.RECOVERY, planId, viewId, null,
                "view.recover", outcome, primary.reasonCode());
    }

    private static List<FailureOccurrence> combine(List<FailureOccurrence> first,
                                                    List<FailureOccurrence> second) {
        Set<FailureOccurrence> combined = new LinkedHashSet<>(first);
        combined.addAll(second);
        return List.copyOf(combined);
    }

    private record FailureOccurrence(String viewId, FailureReference failure) { }

    private record ViewState(ViewOutcome outcome, List<FailureOccurrence> recovered,
                             List<FailureOccurrence> unavailable) { }

    private static final class Consumers {
        private final Set<String> recovered = new LinkedHashSet<>();
        private final Set<String> unrecovered = new LinkedHashSet<>();
    }
}
