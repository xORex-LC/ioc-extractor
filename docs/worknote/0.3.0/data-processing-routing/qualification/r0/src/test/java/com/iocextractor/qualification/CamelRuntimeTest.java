package com.iocextractor.qualification;

import java.util.concurrent.atomic.AtomicInteger;
import org.apache.camel.CamelExecutionException;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.impl.DefaultCamelContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import static org.assertj.core.api.Assertions.*;

/** Isolated admission probe; not the production router or its contract suite. */
@Timeout(20)
class CamelRuntimeTest {
    @Configuration(proxyBeanMethods = false)
    static class ConfigurationUnderTest {
        @Bean AtomicInteger attempts() { return new AtomicInteger(); }
        @Bean(initMethod = "start", destroyMethod = "stop")
        DefaultCamelContext camel(AtomicInteger attempts) throws Exception {
            var context = new DefaultCamelContext();
            context.getShutdownStrategy().setTimeout(2);
            context.addRoutes(new RouteBuilder() {
                @Override public void configure() {
                    errorHandler(noErrorHandler());
                    from("direct:probe").routeId("probe").process(exchange -> {
                        attempts.incrementAndGet();
                        String input = exchange.getMessage().getBody(String.class);
                        if ("fail".equals(input)) throw new IllegalStateException("probe failure");
                        exchange.getMessage().setHeader("executionThread", Thread.currentThread());
                        exchange.getMessage().setBody(input + "-result");
                    });
                }
            });
            return context;
        }
    }

    @Test void bootOwnsStartupFailurePropagationAndShutdown() throws Exception {
        var app = new SpringApplication(ConfigurationUnderTest.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        DefaultCamelContext camel;
        try (var spring = app.run("--spring.main.banner-mode=off")) {
            camel = spring.getBean(DefaultCamelContext.class);
            assertThat(camel.isStarted()).isTrue();
            try (var producer = camel.createProducerTemplate()) {
                var reply = producer.request("direct:probe", exchange -> exchange.getMessage().setBody("value"));
                assertThat(reply.getMessage().getBody(String.class)).isEqualTo("value-result");
                assertThat(reply.getMessage().getHeader("executionThread")).isSameAs(Thread.currentThread());
                assertThatThrownBy(() -> producer.requestBody("direct:probe", "fail", String.class))
                    .isInstanceOf(CamelExecutionException.class)
                    .hasRootCauseInstanceOf(IllegalStateException.class);
                assertThat(spring.getBean(AtomicInteger.class).get()).isEqualTo(2);
            }
        }
        assertThat(camel.isStopped()).isTrue();
    }

    @Test void invalidRouteFailsStartup() throws Exception {
        try (var camel = new DefaultCamelContext()) {
            camel.addRoutes(new RouteBuilder() {
                @Override public void configure() {
                    from("unknown-probe:missing").process(exchange -> { });
                }
            });
            assertThatThrownBy(camel::start).isInstanceOf(Exception.class);
            assertThat(camel.isStarted()).isFalse();
        }
    }
}
