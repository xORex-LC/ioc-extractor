package com.iocextractor.adapter.processing.camel;

import com.iocextractor.adapter.processing.camel.compile.CamelPlanCompiler;
import com.iocextractor.adapter.processing.camel.compile.OperationCatalog;
import com.iocextractor.adapter.processing.camel.compile.PlanAdmissionException;
import com.iocextractor.adapter.processing.camel.contract.Condition;
import com.iocextractor.adapter.processing.camel.contract.PlanDescriptor;
import com.iocextractor.adapter.processing.camel.runtime.CamelRouteRuntime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.camel.Processor;
import org.apache.camel.CamelExecutionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.assertj.core.api.Assertions.*;

@Timeout(15)
class CamelPlanCompilerTest {
    private final AtomicInteger operations = new AtomicInteger();
    private final AtomicInteger destinations = new AtomicInteger();
    private final Processor operation = exchange -> {
        operations.incrementAndGet();
        exchange.getMessage().setBody(exchange.getMessage().getBody(String.class) + "-host");
    };
    private final Processor destination = exchange -> destinations.incrementAndGet();
    private final OperationCatalog catalog = new OperationCatalog(
            Map.of("network.host", operation), Map.of("masks", destination),
            Map.of("type-in", Set.of("types")));
    private final CamelPlanCompiler compiler = new CamelPlanCompiler();

    @Test void compilesOnlyRegisteredLocalRoutesAndPropagatesProcessorResult() throws Exception {
        var plan = new PlanDescriptor("network", List.of(new PlanDescriptor.View("host", "network.host", "original")),
                List.of(new PlanDescriptor.Branch("mask", "masks",
                        new Condition.Leaf("host", "type-in", Map.of("types", "DOMAIN")))));
        var compiled = compiler.compile(List.of(plan), catalog);
        assertThat(compiled.endpointUris()).hasSize(2).allMatch(uri -> uri.startsWith("direct:processing-"));
        try (var runtime = new CamelRouteRuntime(compiled)) {
            assertThat(runtime.request(compiled.endpointUris().get(0), "example", String.class))
                    .isEqualTo("example-host");
            assertThatThrownBy(() -> runtime.request("direct:unregistered", "example", String.class))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(operations).hasValue(1);
            assertThat(destinations).hasValue(0);
            runtime.request(compiled.endpointUris().get(1), "example", String.class);
            assertThat(destinations).hasValue(1);
        }
    }

    @Test void rejectsCyclesDuplicatesAndUnregisteredReferencesBeforeBuildingRoutes() {
        assertRejected(new PlanDescriptor("p", List.of(
                new PlanDescriptor.View("a", "network.host", "b"),
                new PlanDescriptor.View("b", "network.host", "a")), List.of()), "cycle");
        assertRejected(new PlanDescriptor("p", List.of(
                new PlanDescriptor.View("a", "network.host", "original"),
                new PlanDescriptor.View("a", "network.host", "original")), List.of()), "duplicate");
        assertRejected(new PlanDescriptor("p", List.of(
                new PlanDescriptor.View("a", "missing", "original")), List.of()), "unregistered operation");
        assertRejected(new PlanDescriptor("p", List.of(), List.of(
                new PlanDescriptor.Branch("a", "missing", null))), "unregistered destination");
        assertRejected(new PlanDescriptor("p", List.of(), List.of(
                new PlanDescriptor.Branch("a", "masks",
                        new Condition.Leaf("original", "missing", Map.of())))), "unregistered predicate");
        assertRejected(new PlanDescriptor("p", List.of(), List.of(
                new PlanDescriptor.Branch("a", "masks",
                        new Condition.Leaf("original", "type-in", Map.of("unknown", "value"))))),
                "unknown predicate argument");
        assertRejected(new PlanDescriptor("p", List.of(), List.of(
                new PlanDescriptor.Branch("a", "masks", new Condition.All(List.of())))), "empty condition");
    }

    @Test void unexpectedOperationFailureIsPropagatedOnce() throws Exception {
        var attempts = new AtomicInteger();
        Processor failing = exchange -> {
            attempts.incrementAndGet();
            throw new IllegalStateException("operation failed");
        };
        var failingCatalog = new OperationCatalog(Map.of("broken", failing), Map.of(), Map.of());
        var plan = new PlanDescriptor("failure", List.of(
                new PlanDescriptor.View("value", "broken", "original")), List.of());
        var compiled = compiler.compile(List.of(plan), failingCatalog);
        try (var runtime = new CamelRouteRuntime(compiled)) {
            assertThatThrownBy(() -> runtime.request(compiled.endpointUris().getFirst(), "input", String.class))
                    .isInstanceOf(CamelExecutionException.class)
                    .hasRootCauseInstanceOf(IllegalStateException.class);
        }
        assertThat(attempts).hasValue(1);
    }

    @Test void rejectsLimitOverrunsAndAmbiguousIds() {
        var plans = new ArrayList<PlanDescriptor>();
        for (int i = 0; i < 33; i++) {
            plans.add(new PlanDescriptor("plan" + i, List.of(), List.of()));
        }
        assertThatThrownBy(() -> compiler.compile(plans, catalog))
                .isInstanceOf(PlanAdmissionException.class).hasMessageContaining("plan limit");
        var views = new ArrayList<PlanDescriptor.View>();
        for (int i = 0; i < 65; i++) {
            views.add(new PlanDescriptor.View("view" + i, "network.host", "original"));
        }
        assertRejected(new PlanDescriptor("p", views, List.of()), "view or branch limit");
        var branches = new ArrayList<PlanDescriptor.Branch>();
        for (int i = 0; i < 65; i++) {
            branches.add(new PlanDescriptor.Branch("branch" + i, "masks", null));
        }
        assertRejected(new PlanDescriptor("p", List.of(), branches), "view or branch limit");
        Condition deep = new Condition.Leaf("original", "type-in", Map.of());
        for (int i = 0; i < 16; i++) {
            deep = new Condition.Not(deep);
        }
        assertRejected(new PlanDescriptor("p", List.of(), List.of(
                new PlanDescriptor.Branch("a", "masks", deep))), "condition depth limit");
        var leaves = new ArrayList<Condition>();
        for (int i = 0; i < 256; i++) {
            leaves.add(new Condition.Leaf("original", "type-in", Map.of()));
        }
        assertRejected(new PlanDescriptor("p", List.of(), List.of(
                new PlanDescriptor.Branch("a", "masks", new Condition.All(leaves)))),
                "condition node limit");
        assertThatThrownBy(() -> compiler.compile(List.of(
                new PlanDescriptor("p", List.of(), List.of()),
                new PlanDescriptor("p", List.of(), List.of())), catalog))
                .isInstanceOf(PlanAdmissionException.class).hasMessageContaining("duplicate plan");
    }

    @Test void registrySnapshotsPredicateArguments() {
        var allowed = new java.util.HashSet<>(Set.of("types"));
        var snapshot = new OperationCatalog(Map.of(), Map.of(), Map.of("type-in", allowed));
        allowed.add("unsafe");
        assertThat(snapshot.predicateArguments().get("type-in")).containsExactly("types");
        assertThatThrownBy(() -> snapshot.predicateArguments().put("unsafe", Set.of()))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test void rejectsInvalidReferencesAndCountsAcrossBranches() {
        assertRejected(new PlanDescriptor("p", List.of(
                new PlanDescriptor.View("original", "network.host", "original")), List.of()),
                "reserved view ID");
        assertRejected(new PlanDescriptor("p", List.of(
                new PlanDescriptor.View("child", "network.host", "missing")), List.of()),
                "unknown input view");
        assertRejected(new PlanDescriptor("p", List.of(), List.of(
                new PlanDescriptor.Branch("a", "masks", null),
                new PlanDescriptor.Branch("a", "masks", null))), "duplicate branch ID");
        assertRejected(new PlanDescriptor("BAD", List.of(), List.of()), "invalid ID");
        assertRejected(new PlanDescriptor("p", List.of(), List.of(
                new PlanDescriptor.Branch("a", "masks",
                        new Condition.Leaf("missing", "type-in", Map.of())))),
                "unknown condition view");
        var many = new ArrayList<PlanDescriptor.Branch>();
        for (int i = 0; i < 2; i++) {
            var operands = new ArrayList<Condition>();
            for (int j = 0; j < 128; j++) {
                operands.add(new Condition.Leaf("original", "type-in", Map.of()));
            }
            many.add(new PlanDescriptor.Branch("branch" + i, "masks", new Condition.Any(operands)));
        }
        assertRejected(new PlanDescriptor("p", List.of(), many), "condition node limit");
    }

    @Test void admitsSharedDependenciesAndOrderedCompositeConditions() {
        var plan = new PlanDescriptor("p", List.of(
                new PlanDescriptor.View("parent", "network.host", "original"),
                new PlanDescriptor.View("child", "network.host", "parent")), List.of(
                new PlanDescriptor.Branch("a", "masks", new Condition.Any(List.of(
                        new Condition.Leaf("child", "type-in", Map.of()),
                        new Condition.Not(new Condition.Leaf("original", "type-in", Map.of())))))));
        assertThat(compiler.compile(List.of(plan), catalog).endpointUris()).hasSize(3);
    }

    private void assertRejected(PlanDescriptor plan, String reason) {
        assertThatThrownBy(() -> compiler.compile(List.of(plan), catalog))
                .isInstanceOf(PlanAdmissionException.class).hasMessageContaining(reason);
    }
}
