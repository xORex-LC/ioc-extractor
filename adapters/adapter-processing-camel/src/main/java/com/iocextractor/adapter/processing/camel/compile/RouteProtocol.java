package com.iocextractor.adapter.processing.camel.compile;

/** Exchange keys shared by generated branch routes and registered destinations. */
public final class RouteProtocol {
    public static final String PLAN_ID = "ioc.router.plan-id";
    public static final String BRANCH_ID = "ioc.router.branch-id";

    private RouteProtocol() { }
}
