package com.iocextractor.adapter.processing.camel.contract;

/** Disabled trace hook for callers that have no observability bridge. */
public enum NoopRoutingTraceSink implements RoutingTraceSink {
    INSTANCE;

    @Override public boolean isEnabled() { return false; }

    @Override public void trace(RoutingTraceEvent event) { }
}
