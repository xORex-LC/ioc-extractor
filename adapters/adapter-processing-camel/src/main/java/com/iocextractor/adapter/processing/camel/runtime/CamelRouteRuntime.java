package com.iocextractor.adapter.processing.camel.runtime;

import com.iocextractor.adapter.processing.camel.compile.CompiledRoutes;
import java.util.Objects;
import java.util.Set;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.impl.DefaultCamelContext;

/** Owns an isolated embedded Camel context and its local producer. */
public final class CamelRouteRuntime implements AutoCloseable {
    private final DefaultCamelContext context;
    private final ProducerTemplate producer;
    private final Set<String> admittedEndpoints;

    public CamelRouteRuntime(CompiledRoutes compiled) throws Exception {
        Objects.requireNonNull(compiled);
        admittedEndpoints = Set.copyOf(compiled.endpointUris());
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

    /** Invokes one compiled local endpoint; arbitrary input cannot choose a destination. */
    public <T> T request(String endpoint, Object body, Class<T> resultType) {
        if (!admittedEndpoints.contains(endpoint)) {
            throw new IllegalArgumentException("Endpoint is not part of this compiled plan");
        }
        return producer.requestBody(endpoint, body, resultType);
    }

    @Override public void close() throws Exception {
        try {
            producer.stop();
        } finally {
            context.stop();
        }
    }
}
