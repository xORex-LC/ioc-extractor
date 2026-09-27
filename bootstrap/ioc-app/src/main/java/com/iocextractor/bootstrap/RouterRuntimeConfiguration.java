package com.iocextractor.bootstrap;

import com.iocextractor.adapter.processing.camel.compile.CamelPlanCompiler;
import com.iocextractor.adapter.processing.camel.runtime.CamelRouteRuntime;
import com.iocextractor.application.port.out.observability.PipelineDecisionTracer;
import com.iocextractor.observability.EventAction;
import com.iocextractor.observability.EventOutcome;
import com.iocextractor.observability.LogField;
import com.iocextractor.observability.logging.LogEvents;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Activates only a supplied plan; Spring owns startup, readiness and shutdown. */
@Configuration(proxyBeanMethods = false)
public class RouterRuntimeConfiguration {
    private static final Logger LOG = LoggerFactory.getLogger(RouterRuntimeConfiguration.class);

    @Bean(destroyMethod = "close")
    @ConditionalOnBean(RouterPlanRegistration.class)
    CamelRouteRuntime camelRouteRuntime(RouterPlanRegistration registration,
                                       PipelineDecisionTracer tracer) {
        var compiled = new CamelPlanCompiler().compile(registration.plans(),
                registration.catalog(), new RouterMdcScopes());
        CamelRouteRuntime runtime = new CamelRouteRuntime(compiled,
                new RouterTraceBridge(tracer, registration.fingerprint()), Duration.ofSeconds(5));
        for (var plan : registration.plans()) {
            try {
                LogEvents.info(LOG).action(EventAction.ROUTER_PLAN_ADMITTED)
                        .outcome(EventOutcome.SUCCESS)
                        .field(LogField.IOC_ROUTER_PLAN, plan.id())
                        .field(LogField.IOC_ROUTER_FINGERPRINT, registration.fingerprint())
                        .field(LogField.IOC_ROUTER_VIEWS, (long) plan.views().size())
                        .field(LogField.IOC_ROUTER_BRANCHES, (long) plan.routing().branches().size())
                        .field(LogField.IOC_ROUTER_RUNTIME_VERSION, runtime.camelVersion())
                        .message("routing plan ready").log();
            } catch (RuntimeException ignored) {
                // Admission reporting is observational and must not strand the runtime.
            }
        }
        return runtime;
    }

    @Bean
    @ConditionalOnBean(CamelRouteRuntime.class)
    @ConditionalOnProperty(prefix = "ioc.runtime", name = "mode", havingValue = "daemon")
    RouterHealthIndicator routerHealthIndicator(CamelRouteRuntime runtime) {
        return new RouterHealthIndicator(runtime);
    }
}
