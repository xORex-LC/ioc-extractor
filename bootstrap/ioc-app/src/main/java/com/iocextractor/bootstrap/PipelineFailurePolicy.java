package com.iocextractor.bootstrap;

import com.iocextractor.diagnostics.result.FailurePolicy;

/** External selector for the internal diagnostic failure-policy strategy. */
public enum PipelineFailurePolicy {
    FAIL_FAST,
    COLLECT_AND_CONTINUE;

    FailurePolicy toPolicy() {
        return this == FAIL_FAST ? FailurePolicy.failFast() : FailurePolicy.collectAndContinue();
    }
}
