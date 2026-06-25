package com.iocextractor.application.aggregation;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Durable aggregation run snapshot used for crash recovery and observability.
 */
public record AggregationRun(String runId,
                             AggregationRunStatus status,
                             List<String> artifacts,
                             Instant startedAt,
                             Instant updatedAt,
                             String reason) {

    public AggregationRun {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(status, "status");
        artifacts = artifacts == null ? List.of() : List.copyOf(artifacts);
    }
}
