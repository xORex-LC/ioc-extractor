package com.iocextractor.adapter.processing.camel.compile;

import com.iocextractor.adapter.processing.camel.contract.PlanDescriptor;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.apache.camel.builder.RouteBuilder;

/** Compiles bounded local operation and destination routes from admitted plans. */
public final class CamelPlanCompiler {
    private static final int MAX_PLANS = 32;

    /** Compiles one condition graph and Camel route set per admitted plan. */
    public CompiledRoutes compile(List<PlanDescriptor> plans, OperationCatalog catalog) {
        if (plans.size() > MAX_PLANS) {
            throw new PlanAdmissionException("plans", "plan limit exceeded");
        }
        List<RouteBuilder> routes = new ArrayList<>();
        List<String> uris = new ArrayList<>();
        Map<String, CompiledRoutes.CompiledPlan> compiledPlans = new LinkedHashMap<>();
        Set<String> planIds = new HashSet<>();
        for (PlanDescriptor plan : plans) {
            if (!planIds.add(plan.id())) {
                throw new PlanAdmissionException("plans." + plan.id(), "duplicate plan ID");
            }
            PlanValidator.validate(plan, catalog.operations().keySet(),
                    catalog.destinations().keySet(), catalog.predicates());
            Map<String, CompiledRoutes.ViewRoute> viewRoutes = new LinkedHashMap<>();
            for (PlanDescriptor.View view : plan.views()) {
                String uri = address(plan.id(), "view", view.id());
                uris.add(uri);
                viewRoutes.put(view.id(), new CompiledRoutes.ViewRoute(view.input(), uri));
                routes.add(new RouteBuilder() {
                    @Override public void configure() {
                        errorHandler(noErrorHandler());
                        from(uri).routeId(routeId(plan.id(), "view", view.id()))
                                .process(catalog.operations().get(view.operation()));
                    }
                });
            }
            Map<String, String> branchRoutes = new LinkedHashMap<>();
            for (PlanDescriptor.Branch branch : plan.routing().branches()) {
                addBranchRoute(plan.id(), branch, catalog, uris, routes, branchRoutes);
            }
            if (plan.routing().defaultBranch() != null) {
                addBranchRoute(plan.id(), plan.routing().defaultBranch(), catalog,
                        uris, routes, branchRoutes);
            }
            String dispatchUri = address(plan.id(), "dispatch", "selected");
            uris.add(dispatchUri);
            routes.add(dispatchRoute(plan.id(), dispatchUri));
            compiledPlans.put(plan.id(), new CompiledRoutes.CompiledPlan(
                    viewRoutes, branchRoutes, dispatchUri,
                    CompiledSelector.compile(plan.routing(), catalog)));
        }
        return new CompiledRoutes(routes, uris, compiledPlans);
    }

    private static void addBranchRoute(String planId, PlanDescriptor.Branch branch,
                                       OperationCatalog catalog, List<String> uris,
                                       List<RouteBuilder> routes, Map<String, String> branchRoutes) {
        String uri = address(planId, "branch", branch.id());
        uris.add(uri);
        branchRoutes.put(branch.id(), uri);
        routes.add(new RouteBuilder() {
            @Override public void configure() {
                errorHandler(noErrorHandler());
                from(uri).routeId(routeId(planId, "branch", branch.id()))
                        .process(catalog.destinations().get(branch.destination()))
                        .setHeader(RouteProtocol.BRANCH_ID, constant(branch.id()));
            }
        });
    }

    private static RouteBuilder dispatchRoute(String planId, String uri) {
        return new RouteBuilder() {
            @Override public void configure() {
                errorHandler(noErrorHandler());
                from(uri).routeId(routeId(planId, "dispatch", "selected"))
                        .process(exchange -> {
                            DispatchRequest request = Objects.requireNonNull(
                                    exchange.getMessage().getBody(DispatchRequest.class),
                                    "dispatch request");
                            exchange.setProperty(RouteProtocol.RECIPIENTS, request.recipients());
                            exchange.getMessage().setBody(request.input());
                        })
                        .recipientList(exchangeProperty(RouteProtocol.RECIPIENTS))
                        .aggregationStrategy(new BranchReplyAggregationStrategy())
                        .stopOnException()
                        .allowedSchemes("direct");
            }
        };
    }

    private static String address(String plan, String kind, String item) {
        return "direct:" + routeId(plan, kind, item);
    }

    private static String routeId(String plan, String kind, String item) {
        return "processing-" + plan.length() + "-" + plan + "-" + kind + "-"
                + item.length() + "-" + item;
    }
}
