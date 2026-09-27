package com.iocextractor.adapter.processing.camel;

import com.iocextractor.adapter.processing.camel.compile.CamelPlanCompiler;
import com.iocextractor.adapter.processing.camel.compile.OperationCatalog;
import com.iocextractor.adapter.processing.camel.compile.OperationCatalog.PredicateRegistration;
import com.iocextractor.adapter.processing.camel.compile.PlanAdmissionException;
import com.iocextractor.adapter.processing.camel.contract.BranchOutcome;
import com.iocextractor.adapter.processing.camel.contract.Condition;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.assertj.core.api.Assertions.*;

@Timeout(20)
class CamelRecoveryExecutionTest {
    private static final FailureReference HOST_FAILURE = new FailureReference("host", "NO_HOST");
    private static final FailureReference ALTERNATE_FAILURE =
            new FailureReference("alternate", "NO_ALTERNATE");
    private final AtomicInteger hostCalls = new AtomicInteger();
    private final AtomicInteger alternateCalls = new AtomicInteger();
    private final AtomicInteger destinationCalls = new AtomicInteger();
    private final CamelPlanCompiler compiler = new CamelPlanCompiler();
    private final OperationCatalog catalog = new OperationCatalog(Map.of(
            "host", exchange -> {
                hostCalls.incrementAndGet();
                String input = exchange.getMessage().getBody(String.class);
                if ("explode".equals(input)) {
                    throw new IllegalStateException("unexpected host failure");
                }
                exchange.getMessage().setBody("clean".equals(input)
                        ? new ViewOutcome.Available("clean.example")
                        : new ViewOutcome.Unavailable(HOST_FAILURE));
            },
            "alternate", exchange -> {
                alternateCalls.incrementAndGet();
                String input = exchange.getMessage().getBody(String.class);
                exchange.getMessage().setBody("alt-fail".equals(input)
                        ? new ViewOutcome.Unavailable(ALTERNATE_FAILURE)
                        : new ViewOutcome.Available("original:" + input));
            },
            "absent", exchange -> exchange.getMessage().setBody(new ViewOutcome.Absent())), Map.of(
            "capture", exchange -> {
                destinationCalls.incrementAndGet();
                var input = exchange.getMessage().getBody(PlanExecutionResult.BranchInput.class);
                exchange.getMessage().setBody(new BranchOutcome.Prepared(input.resolvedViews()));
            },
            "filter", exchange -> exchange.getMessage().setBody(new BranchOutcome.Filtered()),
            "expected", exchange -> exchange.getMessage().setBody(
                    new BranchOutcome.Unavailable(new FailureReference("mapping", "BAD_ROW")))),
            Map.of("equals", new PredicateRegistration(Set.of("value"),
                            (value, args) -> value.equals(args.get("value"))),
                    "present", new PredicateRegistration(Set.of(),
                            (value, args) -> !(value instanceof ViewOutcome.Absent), true)),
            Set.of("NO_HOST"));

    @Test void primarySuccessDoesNotEvaluateAlternate() throws Exception {
        var plan = plan(List.of(recovered()), List.of(new PlanDescriptor.Branch(
                "recovered", "capture", null, List.of("usable"))));
        try (var runtime = runtime(plan)) {
            var result = runtime.execute("p", "clean");
            assertThat(result.selection().selectedBranches()).containsExactly("recovered");
            assertThat(result.recoveryAttempts()).isEmpty();
            assertThat(result.failures()).isEmpty();
            assertThat(hostCalls).hasValue(1);
            assertThat(alternateCalls).hasValue(0);
        }
    }

    @Test void strictAndRecoveredSiblingsShareOneFailureOccurrence() throws Exception {
        var plan = plan(List.of(recovered()), List.of(
                branch("strict", "host"), branch("recovered", "usable")));
        try (var runtime = runtime(plan)) {
            var result = runtime.execute("p", "blocked");
            assertThat(result.selection().selectedBranches()).containsExactly("recovered");
            assertThat(result.selection().blockedBranches()).extracting(
                    PlanSelection.BlockedBranch::branchId).containsExactly("strict");
            assertThat(result.failures()).containsExactly(new PlanExecutionResult.FailureResolution(
                    "host", HOST_FAILURE, List.of("recovered"), List.of("strict")));
            assertThat(result.recoveryAttempts()).containsExactly(
                    new PlanExecutionResult.RecoveryAttempt("usable", "original",
                            HOST_FAILURE, null, true));
            assertThat(result.replies()).hasSize(1);
            assertThat(hostCalls).hasValue(1);
            assertThat(destinationCalls).hasValue(1);
        }
    }

    @Test void fullyRecoveredConsumerHasNoUnrecoveredFailure() throws Exception {
        var plan = plan(List.of(recovered()), List.of(branch("recovered", "usable")));
        try (var runtime = runtime(plan)) {
            var result = runtime.execute("p", "blocked");
            assertThat(result.failures()).containsExactly(new PlanExecutionResult.FailureResolution(
                    "host", HOST_FAILURE, List.of("recovered"), List.of()));
            assertThat(result.replies()).hasSize(1);
            var prepared = (BranchOutcome.Prepared) result.replies().getFirst().outcome();
            @SuppressWarnings("unchecked")
            var resolved = (Map<String, ViewOutcome>) prepared.candidate();
            assertThat(resolved.get("usable")).isEqualTo(new ViewOutcome.Available("blocked"));
            assertThat(resolved.get("host")).isEqualTo(new ViewOutcome.Unavailable(HOST_FAILURE));
        }
    }

    @Test void unneededRecoveryDoesNotEvaluateItsPrimaryOrProduceEvidence() throws Exception {
        var plan = plan(List.of(recovered()), List.of(new PlanDescriptor.Branch(
                "other", "capture", new Condition.Leaf("original", "equals",
                        Map.of("value", "different")))));
        try (var runtime = runtime(plan)) {
            var result = runtime.execute("p", "blocked");
            assertThat(result.selection().status()).isEqualTo(PlanSelection.Status.SKIPPED);
            assertThat(result.failures()).isEmpty();
            assertThat(result.recoveryAttempts()).isEmpty();
            assertThat(hostCalls).hasValue(0);
        }
    }

    @Test void failedAlternatePreservesBothCausalFailuresAndNoDispatch() throws Exception {
        var views = List.of(new PlanDescriptor.View("alternate", "alternate", "original"),
                new PlanDescriptor.View("host", "host", "original"),
                new PlanDescriptor.View("usable", "view.recover", "host",
                        new PlanDescriptor.Recovery("alternate", Set.of("NO_HOST"))));
        var plan = plan(views, List.of(branch("recovered", "usable")));
        try (var runtime = runtime(plan)) {
            var result = runtime.execute("p", "alt-fail");
            assertThat(result.selection().status()).isEqualTo(PlanSelection.Status.BLOCKED);
            assertThat(result.failures()).extracting(PlanExecutionResult.FailureResolution::failure)
                    .containsExactly(HOST_FAILURE, ALTERNATE_FAILURE);
            assertThat(result.failures()).allSatisfy(failure ->
                    assertThat(failure.unrecoveredConsumers()).containsExactly("recovered"));
            assertThat(result.recoveryAttempts()).containsExactly(
                    new PlanExecutionResult.RecoveryAttempt("usable", "alternate",
                            HOST_FAILURE, ALTERNATE_FAILURE, false));
            assertThat(destinationCalls).hasValue(0);
            assertThat(alternateCalls).hasValue(1);
        }
    }

    @Test void nonAllowlistedFailureCannotInvokeAlternate() throws Exception {
        var plan = plan(List.of(new PlanDescriptor.View("host", "host", "original"),
                new PlanDescriptor.View("alternate", "alternate", "original"),
                new PlanDescriptor.View("usable", "view.recover", "host",
                        new PlanDescriptor.Recovery("alternate", Set.of("OTHER")))),
                List.of(branch("recovered", "usable")));
        var otherReasons = new OperationCatalog(catalog.operations(), catalog.destinations(),
                catalog.predicates(), Set.of("OTHER"));
        try (var runtime = runtime(plan, otherReasons)) {
            var result = runtime.execute("p", "blocked");
            assertThat(result.selection().status()).isEqualTo(PlanSelection.Status.BLOCKED);
            assertThat(result.failures()).extracting(PlanExecutionResult.FailureResolution::failure)
                    .containsExactly(HOST_FAILURE);
            assertThat(result.recoveryAttempts()).isEmpty();
            assertThat(alternateCalls).hasValue(0);
        }
    }

    @Test void optionalAbsenceIsDistinctFromFailureAndPresencePredicateMayInspectIt()
            throws Exception {
        var plan = plan(List.of(new PlanDescriptor.View("optional", "absent", "original"),
                new PlanDescriptor.View("derived", "host", "optional")), List.of(
                new PlanDescriptor.Branch("presence", "capture", new Condition.Leaf(
                        "optional", "present", Map.of())),
                new PlanDescriptor.Branch("strict", "capture", new Condition.Leaf(
                        "derived", "equals", Map.of("value", "anything")))));
        try (var runtime = runtime(plan)) {
            var result = runtime.execute("p", "blocked");
            assertThat(result.selection().status()).isEqualTo(PlanSelection.Status.BLOCKED);
            assertThat(result.selection().blockedBranches()).extracting(
                    PlanSelection.BlockedBranch::branchId).containsExactly("strict");
            assertThat(result.failures()).containsExactly(new PlanExecutionResult.FailureResolution(
                    "optional", new FailureReference("optional", "ABSENT_REQUIRED"),
                    List.of(), List.of("strict")));
            assertThat(hostCalls).hasValue(0);
        }
    }

    @Test void absentAlternateRetainsPrimaryAndAlternateFailureReferences() throws Exception {
        var views = List.of(new PlanDescriptor.View("optional", "absent", "original"),
                new PlanDescriptor.View("host", "host", "original"),
                new PlanDescriptor.View("usable", "view.recover", "host",
                        new PlanDescriptor.Recovery("optional", Set.of("NO_HOST"))));
        try (var runtime = runtime(plan(views, List.of(branch("recovered", "usable"))))) {
            var result = runtime.execute("p", "blocked");
            assertThat(result.failures()).extracting(PlanExecutionResult.FailureResolution::failure)
                    .containsExactly(HOST_FAILURE,
                            new FailureReference("optional", "ABSENT_REQUIRED"));
            assertThat(result.recoveryAttempts()).containsExactly(
                    new PlanExecutionResult.RecoveryAttempt("usable", "optional", HOST_FAILURE,
                            new FailureReference("optional", "ABSENT_REQUIRED"), false));
        }
    }

    @Test void mappingOnlyDemandBlocksOneAllBranchWithoutSuppressingItsSibling()
            throws Exception {
        var plan = plan(List.of(new PlanDescriptor.View("host", "host", "original")),
                List.of(new PlanDescriptor.Branch("needs-host", "capture", null,
                                List.of("host")),
                        new PlanDescriptor.Branch("independent", "capture", null)));
        try (var runtime = runtime(plan)) {
            var result = runtime.execute("p", "blocked");
            assertThat(result.selection().selectedBranches())
                    .containsExactly("needs-host", "independent");
            assertThat(result.preparationBlocked()).containsExactly(
                    new PlanSelection.BlockedBranch("needs-host", HOST_FAILURE));
            assertThat(result.replies()).extracting(PlanExecutionResult.BranchReply::branchId)
                    .containsExactly("independent");
            assertThat(result.failures()).containsExactly(new PlanExecutionResult.FailureResolution(
                    "host", HOST_FAILURE, List.of(), List.of("needs-host")));
        }
    }

    @Test void mappingOnlyRecoveryIsDemandedOnceAndSharedAcrossSelectedBranches()
            throws Exception {
        var plan = plan(List.of(recovered()), List.of(
                new PlanDescriptor.Branch("one", "capture", null, List.of("usable")),
                new PlanDescriptor.Branch("two", "capture", null, List.of("usable"))));
        try (var runtime = runtime(plan)) {
            var result = runtime.execute("p", "blocked");
            assertThat(result.failures()).containsExactly(new PlanExecutionResult.FailureResolution(
                    "host", HOST_FAILURE, List.of("one", "two"), List.of()));
            assertThat(result.recoveryAttempts()).hasSize(1);
            assertThat(result.replies()).hasSize(2);
            assertThat(hostCalls).hasValue(1);
        }
    }

    @Test void firstSelectedBranchWithFailedMappingViewDoesNotTryTheNextBranch()
            throws Exception {
        var plan = new PlanDescriptor("p", List.of(new PlanDescriptor.View(
                "host", "host", "original")), new PlanDescriptor.Routing(
                PlanDescriptor.Mode.FIRST, List.of(
                        new PlanDescriptor.Branch("first", "capture", null, List.of("host")),
                        new PlanDescriptor.Branch("later", "capture", null)),
                new PlanDescriptor.OnUnmatched(PlanDescriptor.Action.SKIP, null), null));
        try (var runtime = runtime(plan)) {
            var result = runtime.execute("p", "blocked");
            assertThat(result.selection().selectedBranches()).containsExactly("first");
            assertThat(result.preparationBlocked()).containsExactly(
                    new PlanSelection.BlockedBranch("first", HOST_FAILURE));
            assertThat(result.replies()).isEmpty();
            assertThat(destinationCalls).hasValue(0);
        }
    }

    @Test void typedFilteredAndExpectedDestinationFailuresDoNotReselect() throws Exception {
        var plan = plan(List.of(), List.of(
                new PlanDescriptor.Branch("filtered", "filter", null),
                new PlanDescriptor.Branch("failed", "expected", null)));
        try (var runtime = runtime(plan)) {
            var result = runtime.execute("p", "blocked");
            assertThat(result.replies()).extracting(PlanExecutionResult.BranchReply::outcome)
                    .containsExactly(new BranchOutcome.Filtered(),
                            new BranchOutcome.Unavailable(new FailureReference("mapping", "BAD_ROW")));
            assertThat(result.failures()).isEmpty();
        }
    }

    @Test void unexpectedPrimaryExceptionIsNeverRecovered() throws Exception {
        var plan = plan(List.of(recovered()), List.of(branch("recovered", "usable")));
        try (var runtime = runtime(plan)) {
            assertThatThrownBy(() -> runtime.execute("p", "explode"))
                    .hasRootCauseInstanceOf(IllegalStateException.class);
            assertThat(destinationCalls).hasValue(0);
        }
    }

    @Test void admissionRejectsUnregisteredReasonForwardAlternateAndRecoveryChain() {
        var host = new PlanDescriptor.View("host", "host", "original");
        var recovered = new PlanDescriptor.View("usable", "view.recover", "host",
                new PlanDescriptor.Recovery("original", Set.of("NO_HOST")));
        assertRejected(List.of(host, new PlanDescriptor.View("usable", "view.recover", "host",
                new PlanDescriptor.Recovery("original", Set.of("*")))), "recoverable reason");
        assertRejected(List.of(host, new PlanDescriptor.View("usable", "view.recover", "host",
                new PlanDescriptor.Recovery("original", Set.of("UNKNOWN")))),
                "recoverable reason");
        assertRejected(List.of(host, new PlanDescriptor.View("usable", "view.recover", "host",
                new PlanDescriptor.Recovery("later", Set.of("NO_HOST"))),
                new PlanDescriptor.View("later", "alternate", "original")), "must precede");
        assertRejected(List.of(host, recovered,
                new PlanDescriptor.View("second", "view.recover", "host",
                        new PlanDescriptor.Recovery("usable", Set.of("NO_HOST")))),
                "chained recovery");
        assertRejected(List.of(host, recovered,
                new PlanDescriptor.View("derived", "alternate", "usable"),
                new PlanDescriptor.View("second", "view.recover", "host",
                        new PlanDescriptor.Recovery("derived", Set.of("NO_HOST")))),
                "chained recovery");
        assertRejected(List.of(host, recovered,
                new PlanDescriptor.View("derived", "alternate", "usable"),
                new PlanDescriptor.View("second", "view.recover", "derived",
                        new PlanDescriptor.Recovery("original", Set.of("NO_HOST")))),
                "chained recovery");
        assertRejected(List.of(host,
                new PlanDescriptor.View("derived", "alternate", "host"),
                new PlanDescriptor.View("usable", "view.recover", "host",
                        new PlanDescriptor.Recovery("derived", Set.of("NO_HOST")))),
                "alternate depends on primary");
        assertRejected(List.of(host, new PlanDescriptor.View("usable", "view.recover", "host",
                new PlanDescriptor.Recovery("original", Set.of()))), "recoverable reason");
        assertRejected(List.of(host, new PlanDescriptor.View("usable", "host", "host",
                new PlanDescriptor.Recovery("original", Set.of("NO_HOST")))),
                "requires view.recover");
        assertRejected(List.of(host, new PlanDescriptor.View("usable", "view.recover", "host")),
                "requires recovery edge");
        assertThatThrownBy(() -> compiler.compile(List.of(plan(List.of(host,
                new PlanDescriptor.View("usable", "view.recover", "host",
                        new PlanDescriptor.Recovery("original", Set.of("*")))),
                List.of(branch("recovered", "usable")))),
                new OperationCatalog(catalog.operations(), catalog.destinations(),
                        catalog.predicates(), Set.of("*"))))
                .isInstanceOf(PlanAdmissionException.class).hasMessageContaining("recoverable reason");
        assertRejected(List.of(host, new PlanDescriptor.View("usable", "view.recover", "host",
                new PlanDescriptor.Recovery("host", Set.of("NO_HOST")))),
                "recovery alternate must differ from primary");
        assertRejected(List.of(host, new PlanDescriptor.View("usable", "view.recover", "original",
                new PlanDescriptor.Recovery("host", Set.of("NO_HOST")))),
                "primary must be a derived view");
    }

    @Test void admissionRejectsUnknownOrDuplicateMappingOnlyViews() {
        var unknown = plan(List.of(), List.of(new PlanDescriptor.Branch("mapped", "capture",
                null, List.of("missing"))));
        assertThatThrownBy(() -> compiler.compile(List.of(unknown), catalog))
                .isInstanceOf(PlanAdmissionException.class)
                .hasMessageContaining("unknown required view");
        var duplicate = plan(List.of(), List.of(new PlanDescriptor.Branch("mapped", "capture",
                null, List.of("original", "original"))));
        assertThatThrownBy(() -> compiler.compile(List.of(duplicate), catalog))
                .isInstanceOf(PlanAdmissionException.class)
                .hasMessageContaining("duplicate required view");
    }

    @Test void defaultBranchRequiredViewFailureCannotRestartNoMatchRouting() throws Exception {
        var plan = new PlanDescriptor("p", List.of(new PlanDescriptor.View(
                "host", "host", "original")), new PlanDescriptor.Routing(
                PlanDescriptor.Mode.FIRST, List.of(),
                new PlanDescriptor.OnUnmatched(PlanDescriptor.Action.ROUTE, "fallback"),
                new PlanDescriptor.Branch("fallback", "capture", null, List.of("host"))));
        try (var runtime = runtime(plan)) {
            var result = runtime.execute("p", "blocked");
            assertThat(result.selection().selectedBranches()).containsExactly("fallback");
            assertThat(result.preparationBlocked()).containsExactly(
                    new PlanSelection.BlockedBranch("fallback", HOST_FAILURE));
            assertThat(result.replies()).isEmpty();
            assertThat(destinationCalls).hasValue(0);
        }
    }

    private PlanDescriptor.View[] recovered() {
        return new PlanDescriptor.View[] {
                new PlanDescriptor.View("host", "host", "original"),
                new PlanDescriptor.View("usable", "view.recover", "host",
                        new PlanDescriptor.Recovery("original", Set.of("NO_HOST")))
        };
    }

    private PlanDescriptor.Branch branch(String id, String view) {
        return new PlanDescriptor.Branch(id, "capture",
                new Condition.Leaf(view, "equals", Map.of("value",
                        "host".equals(view) ? "never" : "blocked")));
    }

    private PlanDescriptor plan(List<PlanDescriptor.View> views,
                                List<PlanDescriptor.Branch> branches) {
        return new PlanDescriptor("p", views, new PlanDescriptor.Routing(
                PlanDescriptor.Mode.ALL, branches,
                new PlanDescriptor.OnUnmatched(PlanDescriptor.Action.SKIP, null), null));
    }

    private CamelRouteRuntime runtime(PlanDescriptor plan) throws Exception {
        return runtime(plan, catalog);
    }

    private CamelRouteRuntime runtime(PlanDescriptor plan, OperationCatalog bindings)
            throws Exception {
        return new CamelRouteRuntime(compiler.compile(List.of(plan), bindings));
    }

    private void assertRejected(List<PlanDescriptor.View> views, String expected) {
        var plan = plan(views, List.of(branch("recovered", "usable")));
        assertThatThrownBy(() -> compiler.compile(List.of(plan), catalog))
                .isInstanceOf(PlanAdmissionException.class).hasMessageContaining(expected);
    }
}
