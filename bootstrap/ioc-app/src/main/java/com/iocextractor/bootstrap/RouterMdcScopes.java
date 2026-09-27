package com.iocextractor.bootstrap;

import com.iocextractor.adapter.processing.camel.contract.RoutingExecutionScopes;
import com.iocextractor.observability.LogField;
import com.iocextractor.observability.MdcScope;

/** Restores ambient correlation after each local Camel operation or destination. */
public final class RouterMdcScopes implements RoutingExecutionScopes {
    @Override public AutoCloseable openView(String planId, String viewId) {
        return MdcScope.open().put(LogField.IOC_ROUTER_PLAN, planId)
                .hide(LogField.IOC_ROUTER_BRANCH)
                .put(LogField.IOC_ROUTER_VIEW, viewId);
    }

    @Override public AutoCloseable openBranch(String planId, String branchId) {
        return MdcScope.open().put(LogField.IOC_ROUTER_PLAN, planId)
                .hide(LogField.IOC_ROUTER_VIEW)
                .put(LogField.IOC_ROUTER_BRANCH, branchId);
    }
}
