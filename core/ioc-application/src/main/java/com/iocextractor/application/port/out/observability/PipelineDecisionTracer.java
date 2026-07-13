package com.iocextractor.application.port.out.observability;

import com.iocextractor.application.observability.PipelineItemDecision;

/** Outbound boundary for explicitly enabled structured per-item pipeline traces. */
public interface PipelineDecisionTracer {

    /**
     * Returns whether decision construction is currently useful.
     * Producers must check this before allocating a decision or derived strings.
     */
    boolean isEnabled();

    /** Emits one already-computed decision without affecting processing outcome. */
    void trace(PipelineItemDecision decision);
}
