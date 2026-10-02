package com.iocextractor.adapter.processing.camel.compile;

import com.iocextractor.adapter.processing.camel.contract.Condition;
import com.iocextractor.adapter.processing.camel.contract.FailureReference;
import com.iocextractor.adapter.processing.camel.contract.PlanDescriptor;
import com.iocextractor.adapter.processing.camel.contract.PlanSelection;
import com.iocextractor.adapter.processing.camel.contract.RoutingTraceEvent;
import com.iocextractor.adapter.processing.camel.contract.RoutingTraceSink;
import com.iocextractor.adapter.processing.camel.contract.ViewOutcome;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** One admitted condition graph; no descriptor tree is interpreted per invocation. */
public final class CompiledSelector {
    private final PlanDescriptor.Mode mode;
    private final PlanDescriptor.OnUnmatched onUnmatched;
    private final List<BranchRule> branches;

    private CompiledSelector(PlanDescriptor.Routing routing, List<BranchRule> branches) {
        mode = routing.mode();
        onUnmatched = routing.onUnmatched();
        this.branches = List.copyOf(branches);
    }

    /** Binds registered predicates once after the descriptor is validated. */
    public static CompiledSelector compile(PlanDescriptor.Routing routing, OperationCatalog catalog) {
        List<BranchRule> rules = new ArrayList<>();
        for (PlanDescriptor.Branch branch : routing.branches()) {
            rules.add(new BranchRule(branch.id(), branch.eligibility() == null
                    ? lookup -> Decision.MATCH
                    : compileCondition(branch.eligibility(), catalog.predicates())));
        }
        return new CompiledSelector(routing, rules);
    }

    /** Evaluates reached decisions and emits value-free trace hooks. */
    public PlanSelection select(ViewLookup lookup, String planId, RoutingTraceSink trace) {
        return switch (mode) {
            case FIRST -> first(lookup, planId, trace);
            case ALL -> all(lookup, planId, trace);
            case EXCLUSIVE -> exclusive(lookup, planId, trace);
        };
    }

    private PlanSelection first(ViewLookup lookup, String planId, RoutingTraceSink trace) {
        for (BranchRule branch : branches) {
            Decision decision = evaluate(branch, lookup, planId, trace);
            if (decision.status() == Decision.Status.MATCH) {
                return selection(PlanSelection.Status.MATCHED, List.of(branch.id()), List.of(), List.of());
            }
            if (decision.status() == Decision.Status.BLOCKED) {
                return blocked(branch.id(), decision.failure());
            }
        }
        return noMatch();
    }

    private PlanSelection all(ViewLookup lookup, String planId, RoutingTraceSink trace) {
        List<String> selected = new ArrayList<>();
        List<PlanSelection.BlockedBranch> blocked = new ArrayList<>();
        for (BranchRule branch : branches) {
            Decision decision = evaluate(branch, lookup, planId, trace);
            if (decision.status() == Decision.Status.MATCH) {
                selected.add(branch.id());
            } else if (decision.status() == Decision.Status.BLOCKED) {
                blocked.add(new PlanSelection.BlockedBranch(branch.id(), decision.failure()));
            }
        }
        if (!selected.isEmpty()) {
            return selection(PlanSelection.Status.MATCHED, selected, blocked, List.of());
        }
        return blocked.isEmpty() ? noMatch()
                : selection(PlanSelection.Status.BLOCKED, List.of(), blocked, List.of());
    }

    private PlanSelection exclusive(ViewLookup lookup, String planId, RoutingTraceSink trace) {
        String firstMatch = null;
        for (BranchRule branch : branches) {
            Decision decision = evaluate(branch, lookup, planId, trace);
            if (decision.status() == Decision.Status.BLOCKED) {
                return blocked(branch.id(), decision.failure());
            }
            if (decision.status() == Decision.Status.MATCH) {
                if (firstMatch != null) {
                    return selection(PlanSelection.Status.AMBIGUOUS, List.of(), List.of(),
                            List.of(firstMatch, branch.id()));
                }
                firstMatch = branch.id();
            }
        }
        return firstMatch == null ? noMatch()
                : selection(PlanSelection.Status.MATCHED, List.of(firstMatch), List.of(), List.of());
    }

    private PlanSelection noMatch() {
        return switch (onUnmatched.action()) {
            case SKIP -> selection(PlanSelection.Status.SKIPPED, List.of(), List.of(), List.of());
            case REJECT -> selection(PlanSelection.Status.REJECTED, List.of(), List.of(), List.of());
            case ROUTE -> selection(PlanSelection.Status.MATCHED,
                    List.of(onUnmatched.branch()), List.of(), List.of());
        };
    }

    private static Decision evaluate(BranchRule branch, ViewLookup lookup,
                                     String planId, RoutingTraceSink trace) {
        Decision decision = branch.condition().evaluate(
                new BranchConditionLookup(lookup, trace, planId, branch.id()));
        traceDecision(trace, planId, null, branch.id(), null, decision);
        return decision;
    }

    private static void traceDecision(RoutingTraceSink trace, String planId, String viewId,
                                      String branchId, String predicateId, Decision decision) {
        trace.emit(RoutingTraceEvent.Kind.CONDITION, planId, viewId, branchId, predicateId,
                decision.status().name().toLowerCase(Locale.ROOT),
                decision.failure() == null ? null : decision.failure().reasonCode());
    }

    private static PlanSelection blocked(String branchId, FailureReference failure) {
        return selection(PlanSelection.Status.BLOCKED, List.of(),
                List.of(new PlanSelection.BlockedBranch(branchId, failure)), List.of());
    }

    private static PlanSelection selection(PlanSelection.Status status, List<String> selected,
                                           List<PlanSelection.BlockedBranch> blocked,
                                           List<String> ambiguous) {
        return new PlanSelection(status, selected, blocked, ambiguous);
    }

    private static CompiledCondition compileCondition(Condition condition,
            Map<String, OperationCatalog.PredicateRegistration> predicates) {
        if (condition instanceof Condition.Leaf leaf) {
            OperationCatalog.PredicateRegistration registration = predicates.get(leaf.predicate());
            var predicate = Objects.requireNonNull(registration.factory().bind(leaf.arguments()),
                    "bound predicate " + leaf.predicate());
            return lookup -> {
                ViewOutcome view = lookup.resolve(leaf.view(), registration.acceptsAbsent());
                if (view instanceof ViewOutcome.Unavailable unavailable) {
                    Decision decision = Decision.blocked(unavailable.failure());
                    lookup.traceLeaf(leaf.view(), leaf.predicate(), decision);
                    return decision;
                }
                Object value = view instanceof ViewOutcome.Absent ? view
                        : ((ViewOutcome.Available) view).value();
                Decision decision = predicate.test(value)
                        ? Decision.MATCH : Decision.NO_MATCH;
                lookup.traceLeaf(leaf.view(), leaf.predicate(), decision);
                return decision;
            };
        }
        if (condition instanceof Condition.Not not) {
            CompiledCondition child = compileCondition(not.child(), predicates);
            return lookup -> child.evaluate(lookup).negate();
        }
        boolean requireAll = condition instanceof Condition.All;
        List<Condition> children = requireAll ? ((Condition.All) condition).children()
                : ((Condition.Any) condition).children();
        List<CompiledCondition> compiled = children.stream()
                .map(child -> compileCondition(child, predicates)).toList();
        return lookup -> evaluateGroup(compiled, requireAll, lookup);
    }

    private static Decision evaluateGroup(List<CompiledCondition> children, boolean requireAll,
                                          ConditionLookup lookup) {
        for (CompiledCondition child : children) {
            Decision result = child.evaluate(lookup);
            if (result.status() == Decision.Status.BLOCKED) {
                return result;
            }
            if (requireAll && result.status() == Decision.Status.NO_MATCH) {
                return Decision.NO_MATCH;
            }
            if (!requireAll && result.status() == Decision.Status.MATCH) {
                return Decision.MATCH;
            }
        }
        return requireAll ? Decision.MATCH : Decision.NO_MATCH;
    }

    /** Resolves a view for the reached branch without evaluating another condition. */
    @FunctionalInterface
    public interface ViewLookup {
        ViewOutcome resolve(String branchId, String viewId, boolean acceptsAbsent);
    }

    @FunctionalInterface
    private interface CompiledCondition {
        Decision evaluate(ConditionLookup lookup);
    }

    private interface ConditionLookup {
        ViewOutcome resolve(String viewId, boolean acceptsAbsent);

        void traceLeaf(String viewId, String predicateId, Decision decision);
    }

    private record BranchConditionLookup(ViewLookup lookup, RoutingTraceSink trace,
                                         String planId, String branchId) implements ConditionLookup {
        @Override public ViewOutcome resolve(String viewId, boolean acceptsAbsent) {
            return lookup.resolve(branchId, viewId, acceptsAbsent);
        }

        @Override public void traceLeaf(String viewId, String predicateId, Decision decision) {
            traceDecision(trace, planId, viewId, branchId, predicateId, decision);
        }
    }

    private record BranchRule(String id, CompiledCondition condition) { }

    private record Decision(Status status, FailureReference failure) {
        private static final Decision MATCH = new Decision(Status.MATCH, null);
        private static final Decision NO_MATCH = new Decision(Status.NO_MATCH, null);

        private static Decision blocked(FailureReference failure) {
            return new Decision(Status.BLOCKED, failure);
        }

        private Decision negate() {
            return switch (status) {
                case MATCH -> NO_MATCH;
                case NO_MATCH -> MATCH;
                case BLOCKED -> this;
            };
        }

        private enum Status { MATCH, NO_MATCH, BLOCKED }
    }
}
