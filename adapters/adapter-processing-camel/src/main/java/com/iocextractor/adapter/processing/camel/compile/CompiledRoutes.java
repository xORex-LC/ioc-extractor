package com.iocextractor.adapter.processing.camel.compile;

import com.iocextractor.adapter.processing.camel.contract.PlanDescriptor;
import java.util.List;
import java.util.Map;
import org.apache.camel.builder.RouteBuilder;

/** Immutable route inventory and plan metadata for one isolated Camel context. */
public record CompiledRoutes(List<RouteBuilder> routes, List<String> endpointUris,
                             Map<String, CompiledPlan> plans) {
    public CompiledRoutes {
        routes = List.copyOf(routes);
        endpointUris = List.copyOf(endpointUris);
        plans = Map.copyOf(plans);
    }

    /** Admitted local endpoints and one condition graph for a named plan. */
    public record CompiledPlan(Map<String, ViewRoute> views, Map<String, BranchRoute> branches,
                               CompiledSelector selector) {
        public CompiledPlan {
            views = Map.copyOf(views);
            branches = Map.copyOf(branches);
        }
    }

    /** One view producer's local endpoint and named input dependency. */
    public record ViewRoute(String input, String uri, PlanDescriptor.Recovery recovery) { }

    /** One destination and the views needed only when that branch is selected. */
    public record BranchRoute(String uri, List<String> requiredViews) {
        public BranchRoute { requiredViews = List.copyOf(requiredViews); }
    }
}
