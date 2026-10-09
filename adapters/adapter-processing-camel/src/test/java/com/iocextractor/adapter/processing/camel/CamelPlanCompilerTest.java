package com.iocextractor.adapter.processing.camel;

import com.iocextractor.adapter.processing.camel.compile.CamelPlanCompiler;
import com.iocextractor.adapter.processing.camel.compile.OperationCatalog;
import com.iocextractor.adapter.processing.camel.compile.OperationCatalog.PredicateRegistration;
import com.iocextractor.adapter.processing.camel.compile.PlanAdmissionException;
import com.iocextractor.adapter.processing.camel.contract.Condition;
import com.iocextractor.adapter.processing.camel.contract.BranchOutcome;
import com.iocextractor.adapter.processing.camel.contract.PlanDescriptor;
import com.iocextractor.adapter.processing.camel.contract.PlanExecutionResult;
import com.iocextractor.adapter.processing.camel.contract.ViewOutcome;
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
        exchange.getMessage().setBody(new ViewOutcome.Available(
                exchange.getMessage().getBody(String.class) + "-host"));
    };
    private final Processor destination = exchange -> {
        destinations.incrementAndGet();
        PlanExecutionResult.BranchInput input = exchange.getMessage().getBody(
                PlanExecutionResult.BranchInput.class);
        exchange.getMessage().setBody(new BranchOutcome.Prepared(input.original() + "-mapped"));
    };
    private final OperationCatalog catalog = new OperationCatalog(
            Map.of("network.host", operation), Map.of("masks", destination),
            Map.of("type-in", new PredicateRegistration(Set.of("types"), (value, args) -> true)));
    private final CamelPlanCompiler compiler = new CamelPlanCompiler();

    @Test void compilesOnlyRegisteredLocalRoutesAndPropagatesProcessorResult() throws Exception {
        var plan = plan("network", List.of(new PlanDescriptor.View("host", "network.host", "original")),
                List.of(new PlanDescriptor.Branch("mask", "masks",
                        new Condition.Leaf("host", "type-in", Map.of("types", "DOMAIN")))));
        var compiled = compiler.compile(List.of(plan), catalog);
        assertThat(compiled.endpointUris()).hasSize(2).allMatch(uri -> uri.startsWith("direct:processing-"));
        try (var runtime = new CamelRouteRuntime(compiled)) {
            var result = runtime.execute("network", "example");
            assertThat(result.replies()).extracting(PlanExecutionResult.BranchReply::outcome)
                    .containsExactly(new BranchOutcome.Prepared("example-mapped"));
            assertThatThrownBy(() -> runtime.execute("unregistered", "example"))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(operations).hasValue(1);
            assertThat(destinations).hasValue(1);
        }
    }

    @Test void rejectsCyclesDuplicatesAndUnregisteredReferencesBeforeBuildingRoutes() {
        assertRejected(plan("p", List.of(
                new PlanDescriptor.View("a", "network.host", "b"),
                new PlanDescriptor.View("b", "network.host", "a")), List.of()), "cycle");
        assertRejected(plan("p", List.of(
                new PlanDescriptor.View("a", "network.host", "original"),
                new PlanDescriptor.View("a", "network.host", "original")), List.of()), "duplicate");
        assertRejected(plan("p", List.of(
                new PlanDescriptor.View("a", "missing", "original")), List.of()), "unregistered operation");
        assertRejected(plan("p", List.of(), List.of(
                new PlanDescriptor.Branch("a", "missing", null))), "unregistered destination");
        assertRejected(plan("p", List.of(), List.of(
                new PlanDescriptor.Branch("a", "masks",
                        new Condition.Leaf("original", "missing", Map.of())))), "unregistered predicate");
        assertRejected(plan("p", List.of(), List.of(
                new PlanDescriptor.Branch("a", "masks",
                        new Condition.Leaf("original", "type-in", Map.of("unknown", "value"))))),
                "unknown predicate argument");
        assertRejected(plan("p", List.of(), List.of(
                new PlanDescriptor.Branch("a", "masks", new Condition.All(List.of())))), "empty condition");
    }

    @Test void unexpectedOperationFailureIsPropagatedOnce() throws Exception {
        var attempts = new AtomicInteger();
        Processor failing = exchange -> {
            attempts.incrementAndGet();
            throw new IllegalStateException("operation failed");
        };
        var failingCatalog = new OperationCatalog(Map.of("broken", failing),
                Map.of("masks", destination), catalog.predicates());
        var plan = plan("failure", List.of(
                new PlanDescriptor.View("value", "broken", "original")), List.of(
                new PlanDescriptor.Branch("mask", "masks",
                        new Condition.Leaf("value", "type-in", Map.of()))));
        var compiled = compiler.compile(List.of(plan), failingCatalog);
        try (var runtime = new CamelRouteRuntime(compiled)) {
            assertThatThrownBy(() -> runtime.execute("failure", "input"))
                    .isInstanceOf(CamelExecutionException.class)
                    .hasRootCauseInstanceOf(IllegalStateException.class);
        }
        assertThat(attempts).hasValue(1);
    }

    @Test void rejectsLimitOverrunsAndAmbiguousIds() {
        var plans = new ArrayList<PlanDescriptor>();
        for (int i = 0; i < 33; i++) {
            plans.add(plan("plan" + i, List.of(), List.of()));
        }
        assertThatThrownBy(() -> compiler.compile(plans, catalog))
                .isInstanceOf(PlanAdmissionException.class).hasMessageContaining("plan limit");
        var views = new ArrayList<PlanDescriptor.View>();
        for (int i = 0; i < 65; i++) {
            views.add(new PlanDescriptor.View("view" + i, "network.host", "original"));
        }
        assertRejected(plan("p", views, List.of()), "view or branch limit");
        var branches = new ArrayList<PlanDescriptor.Branch>();
        for (int i = 0; i < 65; i++) {
            branches.add(new PlanDescriptor.Branch("branch" + i, "masks", null));
        }
        assertRejected(plan("p", List.of(), branches), "view or branch limit");
        Condition deep = new Condition.Leaf("original", "type-in", Map.of());
        for (int i = 0; i < 16; i++) {
            deep = new Condition.Not(deep);
        }
        assertRejected(plan("p", List.of(), List.of(
                new PlanDescriptor.Branch("a", "masks", deep))), "condition depth limit");
        var leaves = new ArrayList<Condition>();
        for (int i = 0; i < 256; i++) {
            leaves.add(new Condition.Leaf("original", "type-in", Map.of()));
        }
        assertRejected(plan("p", List.of(), List.of(
                new PlanDescriptor.Branch("a", "masks", new Condition.All(leaves)))),
                "condition node limit");
        assertThatThrownBy(() -> compiler.compile(List.of(
                plan("p", List.of(), List.of()),
                plan("p", List.of(), List.of())), catalog))
                .isInstanceOf(PlanAdmissionException.class).hasMessageContaining("duplicate plan");
    }

    @Test void registrySnapshotsPredicateArguments() {
        var allowed = new java.util.HashSet<>(Set.of("types"));
        var snapshot = new OperationCatalog(Map.of(), Map.of(), Map.of(
                "type-in", new PredicateRegistration(allowed, (value, args) -> true)));
        allowed.add("unsafe");
        assertThat(snapshot.predicates().get("type-in").argumentNames()).containsExactly("types");
        assertThatThrownBy(() -> snapshot.predicates().put("unsafe",
                new PredicateRegistration(Set.of(), (value, args) -> true)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test void rejectsInvalidReferencesAndCountsAcrossBranches() {
        assertRejected(plan("p", List.of(
                new PlanDescriptor.View("original", "network.host", "original")), List.of()),
                "reserved view ID");
        assertRejected(plan("p", List.of(
                new PlanDescriptor.View("child", "network.host", "missing")), List.of()),
                "unknown input view");
        assertRejected(plan("p", List.of(), List.of(
                new PlanDescriptor.Branch("a", "masks", null),
                new PlanDescriptor.Branch("a", "masks", null))), "duplicate branch ID");
        assertRejected(plan("BAD", List.of(), List.of()), "invalid ID");
        assertRejected(plan("p", List.of(), List.of(
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
        assertRejected(plan("p", List.of(), many), "condition node limit");
    }

    @Test void admitsSharedDependenciesAndOrderedCompositeConditions() {
        var plan = plan("p", List.of(
                new PlanDescriptor.View("parent", "network.host", "original"),
                new PlanDescriptor.View("child", "network.host", "parent")), List.of(
                new PlanDescriptor.Branch("a", "masks", new Condition.Any(List.of(
                        new Condition.Leaf("child", "type-in", Map.of()),
                        new Condition.Not(new Condition.Leaf("original", "type-in", Map.of())))))));
        assertThat(compiler.compile(List.of(plan), catalog).endpointUris()).hasSize(3);
    }

    private PlanDescriptor plan(String id, List<PlanDescriptor.View> views,
                                List<PlanDescriptor.Branch> branches) {
        PlanDescriptor.Routing routing = branches.isEmpty()
                ? new PlanDescriptor.Routing(PlanDescriptor.Mode.FIRST, branches,
                        new PlanDescriptor.OnUnmatched(PlanDescriptor.Action.ROUTE, "default"),
                        new PlanDescriptor.Branch("default", "masks", null))
                : new PlanDescriptor.Routing(PlanDescriptor.Mode.FIRST, branches,
                        new PlanDescriptor.OnUnmatched(PlanDescriptor.Action.SKIP, null), null);
        return new PlanDescriptor(id, views, routing);
    }

    private void assertRejected(PlanDescriptor plan, String reason) {
        assertThatThrownBy(() -> compiler.compile(List.of(plan), catalog))
                .isInstanceOf(PlanAdmissionException.class).hasMessageContaining(reason);
    }
}
