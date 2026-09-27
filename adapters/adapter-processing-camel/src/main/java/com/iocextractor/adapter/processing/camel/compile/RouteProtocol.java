package com.iocextractor.adapter.processing.camel.compile;

/** Private exchange keys shared by generated routes and their aggregator. */
public final class RouteProtocol {
    public static final String RECIPIENTS = "ioc.router.recipients";
    public static final String BRANCH_ID = "ioc.router.branch-id";

    private RouteProtocol() { }
}
