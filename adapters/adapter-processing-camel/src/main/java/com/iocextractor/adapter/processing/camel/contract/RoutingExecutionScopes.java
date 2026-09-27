package com.iocextractor.adapter.processing.camel.contract;

/** Caller-owned scopes around local operation and destination execution. */
public interface RoutingExecutionScopes {
    /** Opens a scope for one derived-view operation. */
    AutoCloseable openView(String planId, String viewId);

    /** Opens a scope for one selected destination. */
    AutoCloseable openBranch(String planId, String branchId);
}
