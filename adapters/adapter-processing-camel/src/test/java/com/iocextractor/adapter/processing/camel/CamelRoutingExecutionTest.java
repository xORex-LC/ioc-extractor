package com.iocextractor.adapter.processing.camel;

import com.iocextractor.adapter.processing.camel.compile.CamelPlanCompiler;
import com.iocextractor.adapter.processing.camel.compile.OperationCatalog;
import com.iocextractor.adapter.processing.camel.compile.OperationCatalog.PredicateRegistration;
import com.iocextractor.adapter.processing.camel.compile.PlanAdmissionException;
import com.iocextractor.adapter.processing.camel.contract.Condition;
import com.iocextractor.adapter.processing.camel.contract.BranchOutcome;
import com.iocextractor.adapter.processing.camel.contract.FailureReference;
import com.iocextractor.adapter.processing.camel.contract.PlanDescriptor;
import com.iocextractor.adapter.processing.camel.contract.PlanExecutionResult;
import com.iocextractor.adapter.processing.camel.contract.PlanSelection;
import com.iocextractor.adapter.processing.camel.contract.ViewOutcome;
import com.iocextractor.adapter.processing.camel.runtime.CamelRouteRuntime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.camel.Processor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.assertj.core.api.Assertions.*;

@Timeout(20)
class CamelRoutingExecutionTest {
    private final CamelPlanCompiler compiler = new CamelPlanCompiler();
    private final AtomicInteger predicateCalls = new AtomicInteger();
    private final AtomicInteger destinationCalls = new AtomicInteger();
    private final AtomicInteger operationCalls = new AtomicInteger();
    private final Processor destination = exchange -> {
        destinationCalls.incrementAndGet();
        var input = exchange.getMessage().getBody(PlanExecutionResult.BranchInput.class);
        exchange.getMessage().setBody(new BranchOutcome.Prepared(input.original() + "-reply"));
    };
    private final OperationCatalog catalog = new OperationCatalog(Map.of(
            "host", exchange -> {
                operationCalls.incrementAndGet();
                exchange.getMessage().setBody(new ViewOutcome.Available("host:" +
                        exchange.getMessage().getBody(String.class)));
            },
            "blocked", exchange -> {
                operationCalls.incrementAndGet();
                exchange.getMessage().setBody(new ViewOutcome.Unavailable(
                        new FailureReference("blocked", "NO_HOST")));
            }),
            Map.of("one", destination, "two", destination, "default", destination),
            Map.of("equals", new PredicateRegistration(Set.of("value"), (value, args) -> {
                predicateCalls.incrementAndGet();
                return value.equals(args.get("value"));
            })));

    @Test void bindsArgumentsOncePerLeafAndEvaluatesEveryReachedInvocation() throws Exception {
        var bindings = new AtomicInteger();
        var calls = new AtomicInteger();
        var boundValues = new java.util.ArrayList<String>();
        var predicates = Map.of("equals", PredicateRegistration.parameterized(Set.of("value"), args -> {
            bindings.incrementAndGet();
            String expected = args.get("value");
            boundValues.add(expected);
            return value -> {
                calls.incrementAndGet();
                return expected.equals(value);
            };
        }));
        var boundCatalog = new OperationCatalog(catalog.operations(), catalog.destinations(), predicates);
        var plan = plan(PlanDescriptor.Mode.ALL, List.of(
                branch("first", equals("original", "hit")),
                branch("second", equals("original", "miss"))), PlanDescriptor.Action.SKIP, null);
        var compiled = compiler.compile(List.of(plan), boundCatalog);
        assertThat(bindings).hasValue(2);
        assertThat(boundValues).containsExactly("hit", "miss");
        try (var runtime = new CamelRouteRuntime(compiled)) {
            assertThat(runtime.execute("p", "hit").selection().selectedBranches()).containsExactly("first");
            assertThat(runtime.execute("p", "miss").selection().selectedBranches()).containsExactly("second");
            assertThat(runtime.execute("p", "hit").selection().selectedBranches()).containsExactly("first");
        }
        assertThat(bindings).hasValue(2);
        assertThat(calls).hasValue(6);
    }

    @Test void invalidFactoryResultsFailDuringCompilation() {
        var plan = plan(PlanDescriptor.Mode.FIRST, List.of(branch("first", equals("original", "hit"))),
                PlanDescriptor.Action.SKIP, null);
        var missing = new OperationCatalog(catalog.operations(), catalog.destinations(), Map.of(
                "equals", PredicateRegistration.parameterized(Set.of("value"), args -> null)));
        assertThatThrownBy(() -> compiler.compile(List.of(plan), missing))
                .isInstanceOf(NullPointerException.class).hasMessage("bound predicate equals");
        var failing = new OperationCatalog(catalog.operations(), catalog.destinations(), Map.of(
                "equals", PredicateRegistration.parameterized(Set.of("value"), args -> {
                    throw new IllegalArgumentException("invalid binding");
                })));
        assertThatThrownBy(() -> compiler.compile(List.of(plan), failing))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("invalid binding");
        assertThat(destinationCalls).hasValue(0);
    }

    @Test void firstStopsAtMatchAndNeverRetriesAfterDestinationFailure() throws Exception {
        var branches = List.of(branch("first", equals("original", "hit")),
                branch("second", equals("original", "hit")));
        var plan = plan(PlanDescriptor.Mode.FIRST, branches, PlanDescriptor.Action.SKIP, null);
        try (var runtime = runtime(plan)) {
            var result = runtime.execute("p", "hit");
            assertThat(result.selection().selectedBranches()).containsExactly("first");
            assertThat(result.replies()).extracting(PlanExecutionResult.BranchReply::branchId)
                    .containsExactly("first");
            assertThat(predicateCalls).hasValue(1);
            assertThat(destinationCalls).hasValue(1);
        }

        Processor failing = exchange -> {
            destinationCalls.incrementAndGet();
            throw new IllegalStateException("mapping failed");
        };
        var failureCatalog = new OperationCatalog(catalog.operations(),
                Map.of("one", failing, "two", destination), catalog.predicates());
        try (var runtime = runtime(plan, failureCatalog)) {
            assertThatThrownBy(() -> runtime.execute("p", "hit"))
                    .hasRootCauseInstanceOf(IllegalStateException.class);
            assertThat(destinationCalls).hasValue(2);
        }
    }

    @Test void allSelectsInOrderAndKeepsIndependentMatchWhenAnotherBranchIsBlocked() throws Exception {
        var branches = List.of(branch("first", equals("original", "hit")),
                branch("blocked", equals("missing", "anything")),
                branch("third", equals("original", "hit")));
        var plan = plan(PlanDescriptor.Mode.ALL, branches, PlanDescriptor.Action.ROUTE,
                new PlanDescriptor.Branch("fallback", "default", null),
                new PlanDescriptor.View("missing", "blocked", "original"));
        try (var runtime = runtime(plan)) {
            var result = runtime.execute("p", "hit");
            assertThat(result.selection().status()).isEqualTo(PlanSelection.Status.MATCHED);
            assertThat(result.selection().selectedBranches()).containsExactly("first", "third");
            assertThat(result.selection().blockedBranches())
                    .containsExactly(new PlanSelection.BlockedBranch("blocked",
                            new FailureReference("blocked", "NO_HOST")));
            assertThat(result.replies()).extracting(PlanExecutionResult.BranchReply::branchId)
                    .containsExactly("first", "third");
            assertThat(destinationCalls).hasValue(2);
            assertThat(operationCalls).hasValue(1);
        }
    }

    @Test void firstAndExclusiveStopOnReachedBlockageWithoutDispatch() throws Exception {
        for (PlanDescriptor.Mode mode : List.of(PlanDescriptor.Mode.FIRST,
                PlanDescriptor.Mode.EXCLUSIVE)) {
            var plan = plan(mode, List.of(
                    branch("blocked", equals("missing", "anything")),
                    branch("later", equals("original", "hit"))),
                    PlanDescriptor.Action.ROUTE,
                    new PlanDescriptor.Branch("fallback", "default", null),
                    new PlanDescriptor.View("missing", "blocked", "original"));
            try (var runtime = runtime(plan)) {
                var result = runtime.execute("p", "hit");
                assertThat(result.selection().status()).isEqualTo(PlanSelection.Status.BLOCKED);
                assertThat(result.selection().selectedBranches()).isEmpty();
                assertThat(result.replies()).isEmpty();
            }
        }
        assertThat(predicateCalls).hasValue(0);
        assertThat(destinationCalls).hasValue(0);
    }

    @Test void exclusiveRequiresAllLaterNoMatchesAndStopsAtSecondMatch() throws Exception {
        var branches = List.of(branch("first", equals("original", "hit")),
                branch("second", equals("original", "hit")),
                branch("third", equals("original", "hit")));
        try (var runtime = runtime(plan(PlanDescriptor.Mode.EXCLUSIVE, branches,
                PlanDescriptor.Action.SKIP, null))) {
            var result = runtime.execute("p", "hit");
            assertThat(result.selection().status()).isEqualTo(PlanSelection.Status.AMBIGUOUS);
            assertThat(result.selection().ambiguousBranches()).containsExactly("first", "second");
            assertThat(result.replies()).isEmpty();
            assertThat(predicateCalls).hasValue(2);
            assertThat(destinationCalls).hasValue(0);
        }
        var unique = List.of(branch("first", equals("original", "hit")),
                branch("second", equals("original", "miss")));
        try (var runtime = runtime(plan(PlanDescriptor.Mode.EXCLUSIVE, unique,
                PlanDescriptor.Action.SKIP, null))) {
            assertThat(runtime.execute("p", "hit").selection().selectedBranches())
                    .containsExactly("first");
            assertThat(predicateCalls).hasValue(4);
        }
    }

    @Test void exclusiveCannotDispatchAnEarlierMatchWhenALaterBranchIsBlocked() throws Exception {
        var plan = plan(PlanDescriptor.Mode.EXCLUSIVE, List.of(
                branch("first", equals("original", "hit")),
                branch("blocked", equals("missing", "anything"))),
                PlanDescriptor.Action.SKIP, null,
                new PlanDescriptor.View("missing", "blocked", "original"));
        try (var runtime = runtime(plan)) {
            var result = runtime.execute("p", "hit");
            assertThat(result.selection().status()).isEqualTo(PlanSelection.Status.BLOCKED);
            assertThat(result.selection().selectedBranches()).isEmpty();
            assertThat(result.selection().blockedBranches()).extracting(
                    PlanSelection.BlockedBranch::branchId).containsExactly("blocked");
            assertThat(result.replies()).isEmpty();
            assertThat(predicateCalls).hasValue(1);
            assertThat(destinationCalls).hasValue(0);
        }
    }

    @Test void allWithOnlyNoMatchAndBlockedDoesNotTakeDefault() throws Exception {
        var plan = plan(PlanDescriptor.Mode.ALL, List.of(
                branch("first", equals("original", "miss")),
                branch("blocked", equals("missing", "anything"))),
                PlanDescriptor.Action.ROUTE,
                new PlanDescriptor.Branch("fallback", "default", null),
                new PlanDescriptor.View("missing", "blocked", "original"));
        try (var runtime = runtime(plan)) {
            var result = runtime.execute("p", "hit");
            assertThat(result.selection().status()).isEqualTo(PlanSelection.Status.BLOCKED);
            assertThat(result.selection().selectedBranches()).isEmpty();
            assertThat(result.selection().blockedBranches()).hasSize(1);
            assertThat(result.replies()).isEmpty();
            assertThat(destinationCalls).hasValue(0);
        }
    }

    @Test void allAndExclusiveUseNoMatchActionOnlyAfterConclusiveEvaluation() throws Exception {
        for (PlanDescriptor.Mode mode : List.of(PlanDescriptor.Mode.ALL,
                PlanDescriptor.Mode.EXCLUSIVE)) {
            var plan = plan(mode, List.of(branch("first", equals("original", "miss"))),
                    PlanDescriptor.Action.ROUTE,
                    new PlanDescriptor.Branch("fallback", "default", null));
            try (var runtime = runtime(plan)) {
                var result = runtime.execute("p", "hit");
                assertThat(result.selection().selectedBranches()).containsExactly("fallback");
                assertThat(result.replies()).extracting(PlanExecutionResult.BranchReply::branchId)
                        .containsExactly("fallback");
            }
        }
        assertThat(destinationCalls).hasValue(2);
    }

    @Test void unconditionalBranchAndConclusiveCompositeConditionsRetainOrder() throws Exception {
        var plan = plan(PlanDescriptor.Mode.ALL, List.of(
                branch("unconditional", null),
                branch("all", new Condition.All(List.of(equals("original", "hit"),
                        equals("original", "hit")))),
                branch("any", new Condition.Any(List.of(equals("original", "miss"),
                        equals("original", "miss")))),
                branch("not", new Condition.Not(equals("original", "hit")))),
                PlanDescriptor.Action.SKIP, null);
        try (var runtime = runtime(plan)) {
            var result = runtime.execute("p", "hit");
            assertThat(result.selection().selectedBranches())
                    .containsExactly("unconditional", "all");
            assertThat(predicateCalls).hasValue(5);
            assertThat(destinationCalls).hasValue(2);
        }
    }

    @Test void failedAncestorViewBlocksDependentConditionWithoutRunningItsOperation()
            throws Exception {
        var plan = plan(PlanDescriptor.Mode.FIRST, List.of(
                branch("first", equals("dependent", "anything"))),
                PlanDescriptor.Action.SKIP, null,
                new PlanDescriptor.View("missing", "blocked", "original"),
                new PlanDescriptor.View("dependent", "host", "missing"));
        try (var runtime = runtime(plan)) {
            var result = runtime.execute("p", "hit");
            assertThat(result.selection().status()).isEqualTo(PlanSelection.Status.BLOCKED);
            assertThat(result.selection().blockedBranches()).extracting(
                    PlanSelection.BlockedBranch::failure)
                    .containsExactly(new FailureReference("blocked", "NO_HOST"));
            assertThat(operationCalls).hasValue(1);
            assertThat(predicateCalls).hasValue(0);
        }
    }

    @Test void operationReturningNoOutcomeFailsTheInvocation() throws Exception {
        var missingOutcome = new OperationCatalog(Map.of("empty", exchange ->
                exchange.getMessage().setBody(null)), catalog.destinations(), catalog.predicates());
        var plan = plan(PlanDescriptor.Mode.FIRST, List.of(branch("first",
                equals("empty", "anything"))), PlanDescriptor.Action.SKIP, null,
                new PlanDescriptor.View("empty", "empty", "original"));
        try (var runtime = runtime(plan, missingOutcome)) {
            assertThatThrownBy(() -> runtime.execute("p", "hit"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Operation returned no view outcome");
            assertThat(destinationCalls).hasValue(0);
        }
    }

    @Test void orderedCompositeConditionsShortCircuitAndNotPreservesBlocked() throws Exception {
        Condition falseBeforeBlocked = new Condition.All(List.of(equals("original", "miss"),
                equals("missing", "anything")));
        Condition trueBeforeBlocked = new Condition.Any(List.of(equals("original", "hit"),
                equals("missing", "anything")));
        Condition blockedBeforeTrue = new Condition.Any(List.of(equals("missing", "anything"),
                equals("original", "hit")));
        Condition blockedNot = new Condition.Not(equals("missing", "anything"));
        var plan = plan(PlanDescriptor.Mode.ALL, List.of(
                branch("false", falseBeforeBlocked), branch("true", trueBeforeBlocked),
                branch("not", new Condition.Not(equals("original", "miss"))),
                branch("blocked", blockedNot), branch("blocked-any", blockedBeforeTrue)),
                PlanDescriptor.Action.SKIP, null,
                new PlanDescriptor.View("missing", "blocked", "original"));
        try (var runtime = runtime(plan)) {
            var result = runtime.execute("p", "hit");
            assertThat(result.selection().selectedBranches()).containsExactly("true", "not");
            assertThat(result.selection().blockedBranches()).hasSize(2);
            assertThat(destinationCalls).hasValue(2);
            assertThat(predicateCalls).hasValue(3);
            assertThat(operationCalls).hasValue(1);
        }
    }

    @Test void noMatchActionsAreDistinctAndDefaultOnlyPlanReallyDispatches() throws Exception {
        var noMatch = List.of(branch("first", equals("original", "miss")));
        for (PlanDescriptor.Action action : List.of(PlanDescriptor.Action.SKIP,
                PlanDescriptor.Action.REJECT)) {
            try (var runtime = runtime(plan(PlanDescriptor.Mode.FIRST, noMatch, action, null))) {
                var result = runtime.execute("p", "hit");
                assertThat(result.selection().status()).isEqualTo(action == PlanDescriptor.Action.SKIP
                        ? PlanSelection.Status.SKIPPED : PlanSelection.Status.REJECTED);
                assertThat(result.replies()).isEmpty();
            }
        }
        try (var runtime = runtime(plan(PlanDescriptor.Mode.FIRST, List.of(),
                PlanDescriptor.Action.ROUTE,
                new PlanDescriptor.Branch("fallback", "default", null)))) {
            var result = runtime.execute("p", "hit");
            assertThat(result.selection().selectedBranches()).containsExactly("fallback");
            assertThat(result.replies()).extracting(PlanExecutionResult.BranchReply::branchId)
                    .containsExactly("fallback");
        }
        assertThat(destinationCalls).hasValue(1);
    }

    @Test void sharedViewIsComputedOncePerInvocationAndNeverAcrossInvocations() throws Exception {
        var plan = plan(PlanDescriptor.Mode.ALL, List.of(
                branch("first", equals("host", "host:hit")),
                branch("second", equals("host", "host:hit"))),
                PlanDescriptor.Action.SKIP, null,
                new PlanDescriptor.View("host", "host", "original"));
        try (var runtime = runtime(plan)) {
            assertThat(runtime.execute("p", "hit").replies()).hasSize(2);
            assertThat(operationCalls).hasValue(1);
            assertThat(runtime.execute("p", "hit").replies()).hasSize(2);
            assertThat(operationCalls).hasValue(2);
        }
    }

    @Test void unexpectedPredicateFailureAbortsAllBeforeAnyDestinationRuns() throws Exception {
        var failing = new OperationCatalog(catalog.operations(), catalog.destinations(), Map.of(
                "equals", new PredicateRegistration(Set.of("value"), (value, args) -> {
                    throw new IllegalArgumentException("predicate failed");
                })));
        var plan = plan(PlanDescriptor.Mode.ALL, List.of(
                branch("first", equals("original", "hit")),
                branch("second", equals("original", "hit"))), PlanDescriptor.Action.SKIP, null);
        try (var runtime = runtime(plan, failing)) {
            assertThatThrownBy(() -> runtime.execute("p", "hit"))
                    .isInstanceOf(IllegalArgumentException.class).hasMessage("predicate failed");
            assertThat(destinationCalls).hasValue(0);
        }
    }

    @Test void admissionRejectsInvalidNoMatchPoliciesBeforeStartingCamel() {
        var regular = branch("first", equals("original", "hit"));
        assertRejected(new PlanDescriptor("p", List.of(), new PlanDescriptor.Routing(
                PlanDescriptor.Mode.FIRST, List.of(),
                new PlanDescriptor.OnUnmatched(PlanDescriptor.Action.SKIP, null), null)),
                "empty branches");
        assertRejected(new PlanDescriptor("p", List.of(), new PlanDescriptor.Routing(
                PlanDescriptor.Mode.FIRST, List.of(regular),
                new PlanDescriptor.OnUnmatched(PlanDescriptor.Action.ROUTE, "missing"), null)),
                "matching default branch");
        assertRejected(new PlanDescriptor("p", List.of(), new PlanDescriptor.Routing(
                PlanDescriptor.Mode.FIRST, List.of(regular),
                new PlanDescriptor.OnUnmatched(PlanDescriptor.Action.ROUTE, "other"),
                new PlanDescriptor.Branch("fallback", "default", null))),
                "matching default branch");
        assertRejected(new PlanDescriptor("p", List.of(), new PlanDescriptor.Routing(
                PlanDescriptor.Mode.FIRST, List.of(regular),
                new PlanDescriptor.OnUnmatched(PlanDescriptor.Action.SKIP, null),
                new PlanDescriptor.Branch("fallback", "default", null))),
                "default branch requires route action");
        assertRejected(new PlanDescriptor("p", List.of(), new PlanDescriptor.Routing(
                PlanDescriptor.Mode.FIRST, List.of(regular),
                new PlanDescriptor.OnUnmatched(PlanDescriptor.Action.ROUTE, "first"),
                new PlanDescriptor.Branch("first", "default", null))),
                "duplicate default branch ID");
        assertRejected(new PlanDescriptor("p", List.of(), new PlanDescriptor.Routing(
                PlanDescriptor.Mode.FIRST, List.of(regular),
                new PlanDescriptor.OnUnmatched(PlanDescriptor.Action.ROUTE, "fallback"),
                new PlanDescriptor.Branch("fallback", "default", equals("original", "hit")))),
                "default branch cannot have eligibility");
        assertRejected(new PlanDescriptor("p", List.of(), new PlanDescriptor.Routing(
                PlanDescriptor.Mode.FIRST, List.of(regular),
                new PlanDescriptor.OnUnmatched(PlanDescriptor.Action.ROUTE, "fallback"),
                new PlanDescriptor.Branch("fallback", "unregistered", null))),
                "unregistered destination");
        assertRejected(new PlanDescriptor("p", List.of(), new PlanDescriptor.Routing(
                PlanDescriptor.Mode.FIRST, List.of(regular),
                new PlanDescriptor.OnUnmatched(PlanDescriptor.Action.SKIP, "fallback"), null)),
                "default branch requires route action");
    }

    private CamelRouteRuntime runtime(PlanDescriptor plan) throws Exception {
        return runtime(plan, catalog);
    }

    private CamelRouteRuntime runtime(PlanDescriptor plan, OperationCatalog bindings) throws Exception {
        return new CamelRouteRuntime(compiler.compile(List.of(plan), bindings));
    }

    private void assertRejected(PlanDescriptor plan, String reason) {
        assertThatThrownBy(() -> compiler.compile(List.of(plan), catalog))
                .isInstanceOf(PlanAdmissionException.class).hasMessageContaining(reason);
    }

    private PlanDescriptor plan(PlanDescriptor.Mode mode, List<PlanDescriptor.Branch> branches,
                                PlanDescriptor.Action action, PlanDescriptor.Branch fallback,
                                PlanDescriptor.View... views) {
        var routing = new PlanDescriptor.Routing(mode, branches,
                new PlanDescriptor.OnUnmatched(action, fallback == null ? null : fallback.id()), fallback);
        return new PlanDescriptor("p", List.of(views), routing);
    }

    private PlanDescriptor.Branch branch(String id, Condition condition) {
        return new PlanDescriptor.Branch(id, id.equals("third") ? "two" : "one", condition);
    }

    private Condition equals(String view, String value) {
        return new Condition.Leaf(view, "equals", Map.of("value", value));
    }
}
