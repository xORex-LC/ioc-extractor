package com.iocextractor.adapter.processing.camel.compile;

/** Exchange keys shared by generated routes, their aggregator and registered destinations. */
public final class RouteProtocol {
    public static final String RECIPIENTS = "ioc.router.recipients";
    public static final String BRANCH_ID = "ioc.router.branch-id";

    private RouteProtocol() { }
}
