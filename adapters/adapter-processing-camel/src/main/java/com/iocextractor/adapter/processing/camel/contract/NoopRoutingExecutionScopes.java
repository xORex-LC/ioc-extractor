package com.iocextractor.adapter.processing.camel.contract;

/** Scope implementation for non-observed technical callers. */
public enum NoopRoutingExecutionScopes implements RoutingExecutionScopes {
    INSTANCE;

    private static final AutoCloseable NOOP = () -> { };

    @Override public AutoCloseable openView(String planId, String viewId) { return NOOP; }

    @Override public AutoCloseable openBranch(String planId, String branchId) { return NOOP; }
}
