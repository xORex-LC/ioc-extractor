package com.iocextractor.application.observability;

import com.iocextractor.application.port.out.observability.PipelineDecisionTracer;

/** Disabled per-item tracer for tests and compositions without operational tracing. */
public enum NoopPipelineDecisionTracer implements PipelineDecisionTracer {
    INSTANCE;

    @Override
    public boolean isEnabled() {
        return false;
    }

    @Override
    public void trace(PipelineItemDecision decision) {
    }
}
