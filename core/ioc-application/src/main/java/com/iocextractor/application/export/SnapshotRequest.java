package com.iocextractor.application.export;

import java.util.Objects;

/** Request to stream one fully resolved export plan from canonical truth. */
public record SnapshotRequest(ExportPlan plan) {

    public SnapshotRequest {
        Objects.requireNonNull(plan, "plan");
    }
}
