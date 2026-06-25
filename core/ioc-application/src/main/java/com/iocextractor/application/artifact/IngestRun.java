package com.iocextractor.application.artifact;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Durable per-file ingest run snapshot used for write-to-project crash recovery.
 */
public record IngestRun(String runId,
                        String sourceKey,
                        IngestRunStatus status,
                        List<String> artifacts,
                        Instant startedAt,
                        Instant updatedAt,
                        String reason) {

    public IngestRun {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(status, "status");
        artifacts = artifacts == null ? List.of() : List.copyOf(artifacts);
    }
}
