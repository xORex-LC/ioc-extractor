package com.iocextractor.adapter.processing.camel.contract;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Selection and ordered branch replies without Camel or durable-write authority. */
public record PlanExecutionResult(PlanSelection selection, List<BranchReply> replies) {
    public PlanExecutionResult {
        Objects.requireNonNull(selection);
        replies = List.copyOf(replies);
    }

    /** Read-only input made available to one selected destination. */
    public record BranchInput(Object original, Map<String, ViewOutcome> resolvedViews) {
        public BranchInput {
            Objects.requireNonNull(original);
            resolvedViews = Map.copyOf(resolvedViews);
        }
    }

    /** One destination's reply, in declared selection order. */
    public record BranchReply(String branchId, Object value) {
        public BranchReply { Objects.requireNonNull(branchId); }
    }
}
