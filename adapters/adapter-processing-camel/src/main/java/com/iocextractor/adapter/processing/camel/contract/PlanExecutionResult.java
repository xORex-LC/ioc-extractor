package com.iocextractor.adapter.processing.camel.contract;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Selection and ordered branch replies without Camel or durable-write authority. */
public record PlanExecutionResult(PlanSelection selection, List<BranchReply> replies,
                                  List<PlanSelection.BlockedBranch> preparationBlocked,
                                  List<FailureResolution> failures,
                                  List<RecoveryAttempt> recoveryAttempts) {
    public PlanExecutionResult {
        Objects.requireNonNull(selection);
        replies = List.copyOf(replies);
        preparationBlocked = List.copyOf(preparationBlocked);
        failures = List.copyOf(failures);
        recoveryAttempts = List.copyOf(recoveryAttempts);
    }

    /** Read-only input made available to one selected destination. */
    public record BranchInput(Object original, Map<String, ViewOutcome> resolvedViews) {
        public BranchInput {
            Objects.requireNonNull(original);
            resolvedViews = Map.copyOf(resolvedViews);
        }
    }

    /** One destination's reply, in declared selection order. */
    public record BranchReply(String branchId, BranchOutcome outcome) {
        public BranchReply {
            Objects.requireNonNull(branchId);
            Objects.requireNonNull(outcome);
        }
    }

    /** One expected failure occurrence and its actually reached consumers. */
    public record FailureResolution(String viewId, FailureReference failure,
                                    List<String> recoveredConsumers,
                                    List<String> unrecoveredConsumers) {
        public FailureResolution {
            Objects.requireNonNull(viewId);
            Objects.requireNonNull(failure);
            recoveredConsumers = List.copyOf(recoveredConsumers);
            unrecoveredConsumers = List.copyOf(unrecoveredConsumers);
        }
    }

    /** A demanded recovery edge; the alternate failure is retained when it fails. */
    public record RecoveryAttempt(String viewId, String alternateView,
                                  FailureReference primaryFailure,
                                  FailureReference alternateFailure, boolean recovered) {
        public RecoveryAttempt {
            Objects.requireNonNull(viewId);
            Objects.requireNonNull(alternateView);
            Objects.requireNonNull(primaryFailure);
        }
    }
}
