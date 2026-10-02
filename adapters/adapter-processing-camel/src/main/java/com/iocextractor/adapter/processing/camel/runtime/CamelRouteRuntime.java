package com.iocextractor.adapter.processing.camel.runtime;

import com.iocextractor.adapter.processing.camel.compile.CompiledRoutes;
import com.iocextractor.adapter.processing.camel.compile.DispatchRequest;
import com.iocextractor.adapter.processing.camel.contract.BranchOutcome;
import com.iocextractor.adapter.processing.camel.contract.FailureReference;
import com.iocextractor.adapter.processing.camel.contract.NoopRoutingTraceSink;
import com.iocextractor.adapter.processing.camel.contract.PlanExecutionResult;
import com.iocextractor.adapter.processing.camel.contract.PlanSelection;
import com.iocextractor.adapter.processing.camel.contract.RoutingTraceEvent;
import com.iocextractor.adapter.processing.camel.contract.RoutingTraceSink;
import com.iocextractor.adapter.processing.camel.contract.ViewOutcome;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.Endpoint;
import org.apache.camel.ServiceStatus;
import org.apache.camel.impl.DefaultCamelContext;

/** Owns an isolated embedded Camel context and its local producer. */
public final class CamelRouteRuntime implements AutoCloseable {
    private final DefaultCamelContext context;
    private final ProducerTemplate producer;
    private final Map<String, Endpoint> endpoints;
    private final Map<String, CompiledRoutes.CompiledPlan> plans;
    private final RoutingTraceSink trace;
    private final long shutdownTimeoutNanos;
    private final Object lifecycle = new Object();
    private boolean closing;
    private int activeCalls;

    public CamelRouteRuntime(CompiledRoutes compiled) {
        this(compiled, NoopRoutingTraceSink.INSTANCE, Duration.ofSeconds(5));
    }

    /** Starts one admitted route set and bounds shutdown of in-flight calls. */
    public CamelRouteRuntime(CompiledRoutes compiled, RoutingTraceSink trace,
                             Duration shutdownTimeout) {
        Objects.requireNonNull(compiled);
        this.trace = Objects.requireNonNull(trace);
        Objects.requireNonNull(shutdownTimeout);
        if (shutdownTimeout.toSeconds() < 1) {
            throw new IllegalArgumentException("shutdownTimeout must be at least one second");
        }
        shutdownTimeoutNanos = shutdownTimeout.toNanos();
        plans = Map.copyOf(compiled.plans());
        context = new DefaultCamelContext();
        context.getShutdownStrategy().setTimeUnit(TimeUnit.MILLISECONDS);
        context.getShutdownStrategy().setTimeout(shutdownTimeout.toMillis());
        context.getShutdownStrategy().setLogInflightExchangesOnTimeout(false);
        try {
            for (var route : compiled.routes()) {
                context.addRoutes(route);
            }
            context.start();
            Map<String, Endpoint> bound = new LinkedHashMap<>();
            for (String uri : compiled.endpointUris()) {
                bound.put(uri, Objects.requireNonNull(context.getEndpoint(uri), "local endpoint " + uri));
            }
            endpoints = Map.copyOf(bound);
            producer = context.createProducerTemplate();
        } catch (Exception failure) {
            try {
                context.stop();
            } catch (Exception shutdownFailure) {
                failure.addSuppressed(shutdownFailure);
            }
            throw new IllegalStateException("Camel routing runtime failed to start", failure);
        }
    }

    /** Selects, resolves demanded views and dispatches one observation. */
    public PlanExecutionResult execute(String planId, Object original) {
        enter();
        try {
            return executeAdmitted(planId, original);
        } finally {
            leave();
        }
    }

    private PlanExecutionResult executeAdmitted(String planId, Object original) {
        Objects.requireNonNull(original);
        CompiledRoutes.CompiledPlan plan = plans.get(planId);
        if (plan == null) {
            throw new IllegalArgumentException("Unknown compiled plan: " + planId);
        }
        InvocationViews views = new InvocationViews(producer, endpoints, original, plan, planId, trace);
        PlanSelection selection = plan.selector().select(views::demand, planId, trace);
        List<Endpoint> recipients = new ArrayList<>();
        List<PlanSelection.BlockedBranch> preparationBlocked = new ArrayList<>();
        for (String branchId : selection.selectedBranches()) {
            CompiledRoutes.BranchRoute branch = plan.branches().get(branchId);
            FailureReference failure = resolveRequiredViews(views, branchId,
                    branch.requiredViews());
            if (failure == null) {
                recipients.add(endpoints.get(branch.uri()));
            } else {
                preparationBlocked.add(new PlanSelection.BlockedBranch(branchId, failure));
                trace.emit(RoutingTraceEvent.Kind.BRANCH, planId, null, branchId,
                        null, "blocked", failure.reasonCode());
            }
        }
        if (recipients.isEmpty()) {
            return result(selection, List.of(), preparationBlocked, views);
        }
        var input = new PlanExecutionResult.BranchInput(original, views.snapshot());
        var request = new DispatchRequest(input, recipients);
        DispatchRequest.Replies replies = Objects.requireNonNull(producer.requestBody(
                endpoints.get(plan.dispatchUri()), request, DispatchRequest.Replies.class), "dispatch replies");
        for (PlanExecutionResult.BranchReply reply : replies.values()) {
            traceReply(planId, reply);
        }
        return result(selection, replies.values(), preparationBlocked, views);
    }

    private void traceReply(String planId, PlanExecutionResult.BranchReply reply) {
        BranchOutcome outcome = reply.outcome();
        String status = outcome instanceof BranchOutcome.Prepared ? "prepared"
                : outcome instanceof BranchOutcome.Filtered ? "filtered" : "unavailable";
        String reason = outcome instanceof BranchOutcome.Unavailable unavailable
                ? unavailable.failure().reasonCode() : null;
        trace.emit(RoutingTraceEvent.Kind.BRANCH, planId, null, reply.branchId(),
                null, status, reason);
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

    /** True only while the context and every generated route are started. */
    public boolean isReady() {
        boolean started = context.isStarted() && context.getRoutes().stream().allMatch(route ->
                context.getRouteController().getRouteStatus(route.getId()) == ServiceStatus.Started);
        synchronized (lifecycle) {
            return !closing && started;
        }
    }

    /** Reports the embedded Camel implementation version for activation evidence. */
    public String camelVersion() {
        return Objects.requireNonNullElse(
                DefaultCamelContext.class.getPackage().getImplementationVersion(), "unknown");
    }

    private void enter() {
        synchronized (lifecycle) {
            if (closing) {
                throw new IllegalStateException("Camel routing runtime is closing");
            }
            activeCalls++;
        }
    }

    private void leave() {
        synchronized (lifecycle) {
            activeCalls--;
            if (activeCalls == 0) {
                lifecycle.notifyAll();
            }
        }
    }

    @Override public void close() throws IOException {
        long started = System.nanoTime();
        long remaining = shutdownTimeoutNanos;
        synchronized (lifecycle) {
            if (closing) {
                return;
            }
            closing = true;
            while (activeCalls > 0 && remaining > 0) {
                try {
                    TimeUnit.NANOSECONDS.timedWait(lifecycle, remaining);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
                remaining = shutdownTimeoutNanos - (System.nanoTime() - started);
            }
        }
        context.getShutdownStrategy().setTimeout(Math.max(1, Duration.ofNanos(
                Math.max(0, remaining)).toMillis()));
        try (producer) {
            context.stop();
        }
    }
}
