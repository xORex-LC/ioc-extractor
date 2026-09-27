package com.iocextractor.adapter.processing.camel.compile;

import java.util.List;
import org.apache.camel.builder.RouteBuilder;

/** Immutable route definitions and their planned endpoint names. */
public record CompiledRoutes(List<RouteBuilder> routes, List<String> endpointUris) {
    public CompiledRoutes {
        routes = List.copyOf(routes);
        endpointUris = List.copyOf(endpointUris);
    }
}
