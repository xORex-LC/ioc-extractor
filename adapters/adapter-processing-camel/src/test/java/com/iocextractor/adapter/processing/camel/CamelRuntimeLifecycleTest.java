package com.iocextractor.adapter.processing.camel;

import com.iocextractor.adapter.processing.camel.compile.CamelPlanCompiler;
import com.iocextractor.adapter.processing.camel.compile.CompiledRoutes;
import com.iocextractor.adapter.processing.camel.compile.OperationCatalog;
import com.iocextractor.adapter.processing.camel.contract.BranchOutcome;
import com.iocextractor.adapter.processing.camel.contract.PlanDescriptor;
import com.iocextractor.adapter.processing.camel.contract.PlanExecutionResult;
import com.iocextractor.adapter.processing.camel.contract.NoopRoutingTraceSink;
import com.iocextractor.adapter.processing.camel.contract.RoutingTraceEvent;
import com.iocextractor.adapter.processing.camel.contract.RoutingTraceSink;
import com.iocextractor.adapter.processing.camel.contract.ViewOutcome;
import com.iocextractor.adapter.processing.camel.runtime.CamelRouteRuntime;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.camel.CamelContext;
import org.apache.camel.builder.RouteBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.assertj.core.api.Assertions.*;

@Timeout(15)
class CamelRuntimeLifecycleTest {
    @Test void startupFailureClosesPartialContextAndInvalidTimeoutNeverStartsOne() {
        var invalidRoutes = new CompiledRoutes(List.of(new RouteBuilder() {
            @Override public void configure() {
                from("missing-component:route").process(exchange -> { });
            }
        }), List.of(), Map.of());
        assertThatThrownBy(() -> new CamelRouteRuntime(invalidRoutes))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("failed to start");
        var compiled = new CamelPlanCompiler().compile(List.of(plan()), catalog(exchange -> { }));
        assertThatThrownBy(() -> new CamelRouteRuntime(compiled,
                NoopRoutingTraceSink.INSTANCE, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void endpointBindingFailureStopsTheStartedContext() {
        var seen = new AtomicReference<CamelContext>();
        var routes = new CompiledRoutes(List.of(new RouteBuilder() {
            @Override public void configure() {
                seen.set(getContext());
                from("direct:valid").process(exchange -> { });
            }
        }), List.of("missing-component:bound"), Map.of());
        assertThatThrownBy(() -> new CamelRouteRuntime(routes))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("failed to start");
        assertThat(seen.get().isStopped()).isTrue();
    }

    @Test void independentRuntimesOwnTheirEndpointsAndClosingOneLeavesTheOtherUsable() throws Exception {
        var contexts = new java.util.HashSet<CamelContext>();
        var catalog = catalog(exchange -> {
            contexts.add(exchange.getContext());
            exchange.getMessage().setBody(new ViewOutcome.Available(exchange.getMessage().getBody()));
        });
        try (var first = runtime(catalog, NoopRoutingTraceSink.INSTANCE, Duration.ofSeconds(2));
             var second = runtime(catalog, NoopRoutingTraceSink.INSTANCE, Duration.ofSeconds(2))) {
            assertThat(candidate(first.execute("p", "first"))).isEqualTo("first");
            assertThat(candidate(second.execute("p", "second"))).isEqualTo("second");
            first.close();
            assertThat(candidate(second.execute("p", "still-running"))).isEqualTo("still-running");
            assertThat(second.isReady()).isTrue();
            assertThat(contexts).hasSize(2);
        }
    }

    @Test void readinessDetectsStoppedRouteAndContext() throws Exception {
        AtomicReference<CamelContext> seen = new AtomicReference<>();
        var catalog = catalog(exchange -> {
            seen.set(exchange.getContext());
            exchange.getMessage().setBody(new ViewOutcome.Available("ready"));
        });
        try (var runtime = runtime(catalog, NoopRoutingTraceSink.INSTANCE, Duration.ofSeconds(2))) {
            runtime.execute("p", "value");
            assertThat(runtime.isReady()).isTrue();
            seen.get().getRouteController().stopRoute(seen.get().getRoutes().getFirst().getId());
            assertThat(runtime.isReady()).isFalse();
            assertThatThrownBy(() -> runtime.execute("p", "must-not-invoke-stopped-view"))
                    .isInstanceOf(RuntimeException.class);
            seen.get().stop();
            assertThat(runtime.isReady()).isFalse();
        }
    }

    @Test void concurrentCallersKeepTheirViewsAndRepliesSeparate() throws Exception {
        CountDownLatch entered = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        var catalog = catalog(exchange -> {
            entered.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("operation did not resume");
            }
            exchange.getMessage().setBody(new ViewOutcome.Available(
                    exchange.getMessage().getBody(String.class)));
        });
        var workers = Executors.newFixedThreadPool(2);
        try (var runtime = runtime(catalog, event -> { }, Duration.ofSeconds(5))) {
            try {
                var first = workers.submit(() -> runtime.execute("p", "first-value"));
                var second = workers.submit(() -> runtime.execute("p", "second-value"));
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                release.countDown();
                assertThat(candidate(first.get(5, TimeUnit.SECONDS))).isEqualTo("first-value");
                assertThat(candidate(second.get(5, TimeUnit.SECONDS))).isEqualTo("second-value");
                assertThat(runtime.isReady()).isTrue();
                assertThat(runtime.camelVersion()).isNotBlank();
            } finally {
                release.countDown();
            }
        } finally {
            workers.shutdownNow();
            assertThat(workers.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test void closeDrainsActiveCallAndRejectsNewOnes() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        var catalog = catalog(exchange -> {
            entered.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("operation did not resume");
            }
            exchange.getMessage().setBody(new ViewOutcome.Available("ready"));
        });
        var runtime = runtime(catalog, event -> { }, Duration.ofSeconds(3));
        var workers = Executors.newFixedThreadPool(2);
        try {
            var invocation = workers.submit(() -> runtime.execute("p", "value"));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            var closing = workers.submit(() -> { runtime.close(); return true; });
            assertThatThrownBy(() -> closing.get(200, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);
            release.countDown();
            assertThat(candidate(invocation.get(5, TimeUnit.SECONDS))).isEqualTo("ready");
            assertThat(closing.get(5, TimeUnit.SECONDS)).isTrue();
            assertThat(runtime.isReady()).isFalse();
            assertThatThrownBy(() -> runtime.execute("p", "later"))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("closing");
        } finally {
            release.countDown();
            runtime.close();
            workers.shutdownNow();
            assertThat(workers.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test void closeIsBoundedWhenAnOperationDoesNotFinish() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        var catalog = catalog(exchange -> {
            entered.countDown();
            release.await(8, TimeUnit.SECONDS);
            exchange.getMessage().setBody(new ViewOutcome.Available("late"));
        });
        var runtime = runtime(catalog, event -> { }, Duration.ofSeconds(1));
        var workers = Executors.newFixedThreadPool(2);
        try {
            var invocation = workers.submit(() -> runtime.execute("p", "value"));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            var closing = workers.submit(() -> { runtime.close(); return true; });
            assertThat(closing.get(4, TimeUnit.SECONDS)).isTrue();
            assertThat(runtime.isReady()).isFalse();
            release.countDown();
            try {
                invocation.get(5, TimeUnit.SECONDS);
            } catch (java.util.concurrent.ExecutionException expectedAfterForcedStop) {
                assertThat(expectedAfterForcedStop.getCause()).isNotNull();
            }
        } finally {
            release.countDown();
            runtime.close();
            workers.shutdownNow();
            assertThat(workers.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test void interruptedShutdownStillStopsTheRuntime() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        var catalog = catalog(exchange -> {
            entered.countDown();
            release.await(5, TimeUnit.SECONDS);
            exchange.getMessage().setBody(new ViewOutcome.Available("done"));
        });
        var runtime = runtime(catalog, NoopRoutingTraceSink.INSTANCE, Duration.ofSeconds(1));
        var workers = Executors.newFixedThreadPool(2);
        try {
            var invocation = workers.submit(() -> runtime.execute("p", "value"));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            var closing = workers.submit(() -> {
                Thread.currentThread().interrupt();
                try {
                    runtime.close();
                    return true;
                } finally {
                    Thread.interrupted();
                }
            });
            assertThat(closing.get(4, TimeUnit.SECONDS)).isTrue();
            assertThat(runtime.isReady()).isFalse();
            release.countDown();
            try {
                invocation.get(5, TimeUnit.SECONDS);
            } catch (java.util.concurrent.ExecutionException expectedAfterForcedStop) {
                assertThat(expectedAfterForcedStop.getCause()).isNotNull();
            }
        } finally {
            release.countDown();
            runtime.close();
            workers.shutdownNow();
            assertThat(workers.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test void traceHookIsValueFreeAndCannotChangeRouting() throws Exception {
        var catalog = catalog(exchange -> exchange.getMessage().setBody(
                new ViewOutcome.Available(exchange.getMessage().getBody(String.class))));
        var events = new java.util.concurrent.CopyOnWriteArrayList<RoutingTraceEvent>();
        RoutingTraceSink recording = new RoutingTraceSink() {
            @Override public boolean isEnabled() { return true; }
            @Override public void trace(RoutingTraceEvent event) { events.add(event); }
        };
        try (var runtime = runtime(catalog, recording, Duration.ofSeconds(3))) {
            assertThat(candidate(runtime.execute("p", "secret-input"))).isEqualTo("secret-input");
            assertThat(events).extracting(RoutingTraceEvent::kind).contains(
                    RoutingTraceEvent.Kind.VIEW, RoutingTraceEvent.Kind.BRANCH);
            assertThat(events.toString()).doesNotContain("secret-input");
            var unsafe = new RoutingTraceEvent(RoutingTraceEvent.Kind.VIEW, "p", "value",
                    null, "https://secret-input/rule", "unavailable", "https://secret-input/path");
            assertThat(unsafe.ruleId()).isEqualTo("INVALID_RULE_ID");
            assertThat(unsafe.reasonCode()).isEqualTo("INVALID_REASON_CODE");
            var valid = new RoutingTraceEvent(RoutingTraceEvent.Kind.RECOVERY, "p", "value",
                    null, "view.recover", "recovered", "NO_HOST");
            assertThat(valid.ruleId()).isEqualTo("view.recover");
            assertThat(valid.reasonCode()).isEqualTo("NO_HOST");
            NoopRoutingTraceSink.INSTANCE.trace(unsafe);
        }
        RoutingTraceSink failing = new RoutingTraceSink() {
            @Override public boolean isEnabled() { return true; }
            @Override public void trace(RoutingTraceEvent event) {
                throw new IllegalStateException("observer failed");
            }
        };
        try (var runtime = runtime(catalog, failing, Duration.ofSeconds(3))) {
            assertThat(candidate(runtime.execute("p", "still-prepared")))
                    .isEqualTo("still-prepared");
        }
    }

    private static CamelRouteRuntime runtime(OperationCatalog catalog,
                                             java.util.function.Consumer<RoutingTraceEvent> observer,
                                             Duration shutdownTimeout) throws Exception {
        RoutingTraceSink sink = new RoutingTraceSink() {
            @Override public boolean isEnabled() { return true; }
            @Override public void trace(RoutingTraceEvent event) { observer.accept(event); }
        };
        return runtime(catalog, sink, shutdownTimeout);
    }

    private static CamelRouteRuntime runtime(OperationCatalog catalog, RoutingTraceSink sink,
                                             Duration shutdownTimeout) throws Exception {
        return new CamelRouteRuntime(new CamelPlanCompiler().compile(List.of(plan()), catalog),
                sink, shutdownTimeout);
    }

    private static PlanDescriptor plan() {
        return new PlanDescriptor("p", List.of(new PlanDescriptor.View("value", "derive", "original")),
                new PlanDescriptor.Routing(PlanDescriptor.Mode.FIRST,
                        List.of(new PlanDescriptor.Branch("branch", "capture", null,
                                List.of("value"))),
                        new PlanDescriptor.OnUnmatched(PlanDescriptor.Action.SKIP, null), null));
    }

    private static OperationCatalog catalog(org.apache.camel.Processor operation) {
        return new OperationCatalog(Map.of("derive", operation),
                Map.of("capture", exchange -> {
                    var input = exchange.getMessage().getBody(PlanExecutionResult.BranchInput.class);
                    var value = (ViewOutcome.Available) input.resolvedViews().get("value");
                    exchange.getMessage().setBody(new BranchOutcome.Prepared(value.value()));
                }), Map.of());
    }

    private static Object candidate(PlanExecutionResult result) {
        return ((BranchOutcome.Prepared) result.replies().getFirst().outcome()).candidate();
    }
}
