package com.iocextractor.adapter.processing.camel.compile;

import com.iocextractor.adapter.processing.camel.contract.PlanDescriptor;
import com.iocextractor.adapter.processing.camel.contract.BranchOutcome;
import com.iocextractor.adapter.processing.camel.contract.PlanExecutionResult.BranchReply;
import com.iocextractor.adapter.processing.camel.contract.NoopRoutingExecutionScopes;
import com.iocextractor.adapter.processing.camel.contract.RoutingExecutionScopes;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.Exchange;

/** Compiles bounded local operation and destination routes from admitted plans. */
public final class CamelPlanCompiler {
    private static final int MAX_PLANS = 32;

    /** Compiles one condition graph and Camel route set per admitted plan. */
    public CompiledRoutes compile(List<PlanDescriptor> plans, OperationCatalog catalog) {
        return compile(plans, catalog, NoopRoutingExecutionScopes.INSTANCE);
    }

    /** Compiles local routes with caller-owned, automatically closed execution scopes. */
    public CompiledRoutes compile(List<PlanDescriptor> plans, OperationCatalog catalog,
                                  RoutingExecutionScopes scopes) {
        Objects.requireNonNull(scopes);
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
                    catalog.destinations().keySet(), catalog.predicates(),
                    catalog.recoverableReasons());
            Map<String, CompiledRoutes.ViewRoute> viewRoutes = new LinkedHashMap<>();
            for (PlanDescriptor.View view : plan.views()) {
                if (view.recovery() != null) {
                    viewRoutes.put(view.id(), new CompiledRoutes.ViewRoute(view.input(),
                            null, view.recovery()));
                } else {
                    String uri = address(plan.id(), "view", view.id());
                    uris.add(uri);
                    viewRoutes.put(view.id(), new CompiledRoutes.ViewRoute(view.input(), uri, null));
                    var operation = catalog.operations().get(view.operation());
                    routes.add(new RouteBuilder() {
                        @Override public void configure() {
                            errorHandler(noErrorHandler());
                            from(uri).routeId(routeId(plan.id(), "view", view.id()))
                                    .process(exchange -> {
                                        try (var ignored = scopes.openView(plan.id(), view.id())) {
                                            operation.process(exchange);
                                        }
                                    });
                        }
                    });
                }
            }
            Map<String, CompiledRoutes.BranchRoute> branchRoutes = new LinkedHashMap<>();
            for (PlanDescriptor.Branch branch : plan.routing().branches()) {
                addBranchRoute(plan.id(), branch, catalog, scopes, uris, routes, branchRoutes);
            }
            if (plan.routing().defaultBranch() != null) {
                addBranchRoute(plan.id(), plan.routing().defaultBranch(), catalog, scopes,
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
                                       OperationCatalog catalog, RoutingExecutionScopes scopes,
                                       List<String> uris,
                                       List<RouteBuilder> routes,
                                       Map<String, CompiledRoutes.BranchRoute> branchRoutes) {
        String uri = address(planId, "branch", branch.id());
        uris.add(uri);
        branchRoutes.put(branch.id(), new CompiledRoutes.BranchRoute(uri, branch.requiredViews()));
        var destination = catalog.destinations().get(branch.destination());
        routes.add(new RouteBuilder() {
            @Override public void configure() {
                errorHandler(noErrorHandler());
                from(uri).routeId(routeId(planId, "branch", branch.id()))
                        .setHeader(RouteProtocol.PLAN_ID, constant(planId))
                        .setHeader(RouteProtocol.BRANCH_ID, constant(branch.id()))
                        .process(exchange -> {
                            try (var ignored = scopes.openBranch(planId, branch.id())) {
                                destination.process(exchange);
                            }
                            String id = Objects.requireNonNull(exchange.getMessage().getHeader(
                                    RouteProtocol.BRANCH_ID, String.class), "recipient branch ID");
                            BranchOutcome outcome = Objects.requireNonNull(
                                    exchange.getMessage().getBody(BranchOutcome.class), "recipient outcome");
                            exchange.getMessage().setBody(new BranchReply(id, outcome));
                        });
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
                        .allowedSchemes("direct")
                        .end()
                        .process(CamelPlanCompiler::freezeReplies);
            }
        };
    }

    private static void freezeReplies(Exchange exchange) {
        // Only BranchReplyAggregationStrategy.getValue populates this Camel-owned list.
        @SuppressWarnings("unchecked")
        List<BranchReply> values = Objects.requireNonNull(
                exchange.getMessage().getBody(List.class), "recipient replies");
        exchange.getMessage().setBody(new DispatchRequest.Replies(values));
    }

    private static String address(String plan, String kind, String item) {
        return "direct:" + routeId(plan, kind, item)
                + "?block=false&failIfNoConsumers=true";
    }

    private static String routeId(String plan, String kind, String item) {
        return "processing-" + plan.length() + "-" + plan + "-" + kind + "-"
                + item.length() + "-" + item;
    }
}
