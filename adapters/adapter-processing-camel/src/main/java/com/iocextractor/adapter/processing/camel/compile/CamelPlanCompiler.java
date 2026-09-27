package com.iocextractor.adapter.processing.camel.compile;

import com.iocextractor.adapter.processing.camel.contract.PlanDescriptor;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.apache.camel.builder.RouteBuilder;

/** Compiles admitted operation endpoints; selection and dispatch follow in R2. */
public final class CamelPlanCompiler {
    private static final int MAX_PLANS = 32;

    /** Generates only local routes from registered processors and validated descriptors. */
    public CompiledRoutes compile(List<PlanDescriptor> plans, OperationCatalog catalog) {
        if (plans.size() > MAX_PLANS) {
            throw new PlanAdmissionException("plans", "plan limit exceeded");
        }
        List<RouteBuilder> routes = new ArrayList<>();
        List<String> uris = new ArrayList<>();
        Set<String> planIds = new HashSet<>();
        for (PlanDescriptor plan : plans) {
            if (!planIds.add(plan.id())) {
                throw new PlanAdmissionException("plans." + plan.id(), "duplicate plan ID");
            }
            PlanValidator.validate(plan, catalog.operations().keySet(),
                    catalog.destinations().keySet(), catalog.predicateArguments());
            for (PlanDescriptor.View view : plan.views()) {
                String uri = address(plan.id(), "view", view.id());
                uris.add(uri);
                routes.add(new RouteBuilder() {
                    @Override public void configure() {
                        errorHandler(noErrorHandler());
                        from(uri).routeId(routeId(plan.id(), "view", view.id()))
                                .process(catalog.operations().get(view.operation()));
                    }
                });
            }
            for (PlanDescriptor.Branch branch : plan.branches()) {
                String uri = address(plan.id(), "branch", branch.id());
                uris.add(uri);
                routes.add(new RouteBuilder() {
                    @Override public void configure() {
                        errorHandler(noErrorHandler());
                        from(uri).routeId(routeId(plan.id(), "branch", branch.id()))
                                .process(catalog.destinations().get(branch.destination()));
                    }
                });
            }
        }
        return new CompiledRoutes(routes, uris);
    }

    private static String address(String plan, String kind, String item) {
        return "direct:" + routeId(plan, kind, item);
    }

    private static String routeId(String plan, String kind, String item) {
        return "processing-" + plan.length() + "-" + plan + "-" + kind + "-"
                + item.length() + "-" + item;
    }
}
