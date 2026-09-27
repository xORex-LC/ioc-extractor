package com.iocextractor.bootstrap;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.iocextractor.adapter.processing.camel.compile.OperationCatalog;
import com.iocextractor.adapter.processing.camel.contract.BranchOutcome;
import com.iocextractor.adapter.processing.camel.contract.PlanDescriptor;
import com.iocextractor.adapter.processing.camel.contract.PlanExecutionResult;
import com.iocextractor.adapter.processing.camel.contract.ViewOutcome;
import com.iocextractor.adapter.processing.camel.runtime.CamelRouteRuntime;
import com.iocextractor.application.observability.PipelineItemDecision;
import com.iocextractor.application.port.out.observability.PipelineDecisionTracer;
import com.iocextractor.application.tck.junit.IntegrationTest;
import com.iocextractor.observability.LogField;
import com.iocextractor.observability.MdcScope;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.MDC;
import org.slf4j.LoggerFactory;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.*;

/** Spring activation uses only admitted registrations and closes the owned context. */
@IntegrationTest
@Timeout(20)
class RouterRuntimeConfigurationIT {
    @Test void noRegistrationLeavesLegacyRuntimeUnchanged() {
        baseRunner().withPropertyValues("ioc.runtime.mode=daemon").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(CamelRouteRuntime.class);
            assertThat(context).doesNotHaveBean(RouterHealthIndicator.class);
        });
    }

    @Test void suppliedPlanStartsBeforeUseAndClosesWithSpring() {
        AtomicReference<CamelRouteRuntime> owned = new AtomicReference<>();
        AtomicReference<RouterHealthIndicator> health = new AtomicReference<>();
        var decisions = new CopyOnWriteArrayList<PipelineItemDecision>();
        PipelineDecisionTracer tracer = new PipelineDecisionTracer() {
            @Override public boolean isEnabled() { return true; }
            @Override public void trace(PipelineItemDecision decision) { decisions.add(decision); }
        };
        baseRunner().withPropertyValues("ioc.runtime.mode=daemon")
                .withBean(RouterPlanRegistration.class, () -> registration(catalog()))
                .withBean(PipelineDecisionTracer.class, () -> tracer)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(CamelRouteRuntime.class);
                    assertThat(context).hasSingleBean(RouterHealthIndicator.class);
                    CamelRouteRuntime runtime = context.getBean(CamelRouteRuntime.class);
                    owned.set(runtime);
                    assertThat(runtime.isReady()).isTrue();
                    health.set(context.getBean(RouterHealthIndicator.class));
                    assertThat(health.get().health().getStatus())
                            .isEqualTo(Status.UP);
                    var result = runtime.execute("plan", "safe-value");
                    assertThat(((BranchOutcome.Prepared) result.replies().getFirst().outcome())
                            .candidate()).isEqualTo("safe-value");
                    assertThat(decisions).anySatisfy(decision -> {
                        assertThat(decision.planId()).isEqualTo("plan");
                        assertThat(decision.policyFingerprint()).isEqualTo("test-fingerprint");
                        assertThat(decision.routingStep()).isEqualTo("view");
                        assertThat(decision.viewId()).isEqualTo("derived");
                    });
                });
        assertThat(owned.get().isReady()).isFalse();
        assertThat(health.get().health().getStatus()).isEqualTo(Status.DOWN);
    }

    @Test void registrationRejectsMissingPlanAndPolicyIdentity() {
        var bindings = catalog();
        assertThatThrownBy(() -> new RouterPlanRegistration(List.of(), bindings, "fingerprint"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least one plan");
        assertThatThrownBy(() -> new RouterPlanRegistration(List.of(plan()), null, "fingerprint"))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new RouterPlanRegistration(List.of(plan()), bindings, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fingerprint");
        assertThatThrownBy(() -> new RouterPlanRegistration(List.of(plan()), bindings, "  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fingerprint");
    }

    @Test void branchAndViewScopesRestoreParentMdcOnSuccessAndFailure() {
        var seenView = new AtomicReference<Map<String, String>>();
        var seenBranch = new AtomicReference<Map<String, String>>();
        var catalog = new OperationCatalog(Map.of("derive", exchange -> {
            seenView.set(MDC.getCopyOfContextMap());
            if ("explode".equals(exchange.getMessage().getBody(String.class))) {
                throw new IllegalStateException("operation failed");
            }
            exchange.getMessage().setBody(new ViewOutcome.Available("mapped"));
        }), Map.of("capture", exchange -> {
            seenBranch.set(MDC.getCopyOfContextMap());
            exchange.getMessage().setBody(new BranchOutcome.Prepared("mapped"));
        }), Map.of());
        baseRunner().withBean(RouterPlanRegistration.class, () -> registration(catalog))
                .withBean(PipelineDecisionTracer.class, () -> disabledTracer())
                .run(context -> {
                    CamelRouteRuntime runtime = context.getBean(CamelRouteRuntime.class);
                    try (var parent = MdcScope.open()
                            .put(LogField.IOC_RUN_ID, "parent-run")
                            .put(LogField.IOC_ROUTER_PLAN, "parent-plan")
                            .put(LogField.IOC_ROUTER_BRANCH, "parent-branch")) {
                        runtime.execute("plan", "success");
                        assertThat(seenView.get()).containsEntry("ioc.run.id", "parent-run")
                                .containsEntry("ioc.router.plan", "plan")
                                .containsEntry("ioc.router.view", "derived")
                                .doesNotContainKey("ioc.router.branch");
                        assertThat(seenBranch.get()).containsEntry("ioc.run.id", "parent-run")
                                .containsEntry("ioc.router.plan", "plan")
                                .containsEntry("ioc.router.branch", "selected")
                                .doesNotContainKey("ioc.router.view");
                        assertThatThrownBy(() -> runtime.execute("plan", "explode"))
                                .hasRootCauseInstanceOf(IllegalStateException.class);
                        assertThat(MDC.get("ioc.router.plan")).isEqualTo("parent-plan");
                        assertThat(MDC.get("ioc.router.branch")).isEqualTo("parent-branch");
                        assertThat(MDC.get("ioc.router.view")).isNull();
                    }
                });
    }

    @Test void invalidRegisteredPlanFailsContextBeforeReadiness() {
        var invalid = new PlanDescriptor("plan", List.of(new PlanDescriptor.View(
                "derived", "missing", "original")), plan().routing());
        baseRunner().withBean(RouterPlanRegistration.class, () ->
                        new RouterPlanRegistration(List.of(invalid), catalog(), "test-fingerprint"))
                .withBean(PipelineDecisionTracer.class, () -> disabledTracer())
                .run(context -> assertThat(context.getStartupFailure())
                        .hasRootCauseInstanceOf(
                                com.iocextractor.adapter.processing.camel.compile.PlanAdmissionException.class));
    }

    @Test void operationFailurePropagatesWithoutAdditionalCamelErrorLog() {
        var catalog = new OperationCatalog(Map.of("derive", exchange -> {
            throw new IllegalStateException("operation failed");
        }), Map.of("capture", exchange -> exchange.getMessage()
                .setBody(new BranchOutcome.Prepared("unused"))), Map.of());
        Logger camelLogger = (Logger) LoggerFactory.getLogger("org.apache.camel");
        Level previousLevel = camelLogger.getLevel();
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        camelLogger.addAppender(appender);
        camelLogger.setLevel(Level.ERROR);
        try {
            baseRunner().withBean(RouterPlanRegistration.class, () -> registration(catalog))
                    .withBean(PipelineDecisionTracer.class, () -> disabledTracer())
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        assertThatThrownBy(() -> context.getBean(CamelRouteRuntime.class)
                                .execute("plan", "value"))
                                .hasRootCauseInstanceOf(IllegalStateException.class);
                    });
            assertThat(appender.list).noneMatch(event -> event.getLevel() == Level.ERROR);
        } finally {
            camelLogger.detachAppender(appender);
            camelLogger.setLevel(previousLevel);
            appender.stop();
        }
    }

    private static ApplicationContextRunner baseRunner() {
        return new ApplicationContextRunner().withUserConfiguration(RouterRuntimeConfiguration.class);
    }

    private static RouterPlanRegistration registration(OperationCatalog catalog) {
        return new RouterPlanRegistration(List.of(plan()), catalog, "test-fingerprint");
    }

    private static PlanDescriptor plan() {
        return new PlanDescriptor("plan", List.of(new PlanDescriptor.View("derived", "derive", "original")),
                new PlanDescriptor.Routing(PlanDescriptor.Mode.FIRST,
                        List.of(new PlanDescriptor.Branch("selected", "capture", null,
                                List.of("derived"))),
                        new PlanDescriptor.OnUnmatched(PlanDescriptor.Action.SKIP, null), null));
    }

    private static OperationCatalog catalog() {
        return new OperationCatalog(Map.of("derive", exchange -> exchange.getMessage()
                .setBody(new ViewOutcome.Available(exchange.getMessage().getBody(String.class)))),
                Map.of("capture", exchange -> {
                    var input = exchange.getMessage().getBody(PlanExecutionResult.BranchInput.class);
                    exchange.getMessage().setBody(new BranchOutcome.Prepared(
                            ((ViewOutcome.Available) input.resolvedViews().get("derived")).value()));
                }), Map.of());
    }

    private static PipelineDecisionTracer disabledTracer() {
        return new PipelineDecisionTracer() {
            @Override public boolean isEnabled() { return false; }
            @Override public void trace(PipelineItemDecision decision) { }
        };
    }
}
