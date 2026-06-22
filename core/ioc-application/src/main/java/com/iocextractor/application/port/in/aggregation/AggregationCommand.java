package com.iocextractor.application.port.in.aggregation;

import java.util.List;

/**
 * Aggregation command. Artifact filtering is mainly useful for tests and future
 * operator entry points; an empty list means all configured artifacts.
 */
public record AggregationCommand(List<String> artifacts) {

    public AggregationCommand {
        artifacts = artifacts == null ? List.of() : List.copyOf(artifacts);
    }

    public static AggregationCommand allArtifacts() {
        return new AggregationCommand(List.of());
    }
}
