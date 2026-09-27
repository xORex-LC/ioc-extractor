package com.iocextractor.adapter.processing.camel.contract;

/** Optional value-free trace hook; observer failures cannot change routing. */
public interface RoutingTraceSink {
    /** Whether building a trace event is useful for this invocation. */
    boolean isEnabled();

    /** Receives one already-computed decision. */
    void trace(RoutingTraceEvent event);

    /** Emits only when enabled, isolating a failing observer from processing. */
    default void emit(RoutingTraceEvent.Kind kind, String planId, String viewId,
                      String branchId, String ruleId, String outcome, String reasonCode) {
        try {
            if (isEnabled()) {
                trace(new RoutingTraceEvent(kind, planId, viewId, branchId,
                        ruleId, outcome, reasonCode));
            }
        } catch (RuntimeException ignored) {
            // Tracing is observational and must not alter the preparation result.
        }
    }
}
