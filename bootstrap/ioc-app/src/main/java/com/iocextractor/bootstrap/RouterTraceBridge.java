package com.iocextractor.bootstrap;

import com.iocextractor.adapter.processing.camel.contract.RoutingTraceEvent;
import com.iocextractor.adapter.processing.camel.contract.RoutingTraceSink;
import com.iocextractor.application.observability.PipelineDecisionKind;
import com.iocextractor.application.observability.PipelineItemDecision;
import com.iocextractor.application.port.out.observability.PipelineDecisionTracer;
import java.util.Objects;
import java.util.Locale;

/** Maps value-free router hooks to the existing per-item decision tracer. */
public final class RouterTraceBridge implements RoutingTraceSink {
    private final PipelineDecisionTracer delegate;
    private final String policyFingerprint;

    public RouterTraceBridge(PipelineDecisionTracer delegate, String policyFingerprint) {
        this.delegate = Objects.requireNonNull(delegate);
        this.policyFingerprint = Objects.requireNonNull(policyFingerprint);
    }

    @Override public boolean isEnabled() {
        return delegate.isEnabled();
    }

    @Override public void trace(RoutingTraceEvent event) {
        var decision = PipelineItemDecision.builder(PipelineDecisionKind.ROUTING, event.outcome())
                .routing(event.planId(), event.kind().name().toLowerCase(Locale.ROOT),
                        event.viewId(), event.branchId(), event.reasonCode())
                .policyFingerprint(policyFingerprint)
                .rule(event.ruleId())
                .build();
        delegate.trace(decision);
    }
}
