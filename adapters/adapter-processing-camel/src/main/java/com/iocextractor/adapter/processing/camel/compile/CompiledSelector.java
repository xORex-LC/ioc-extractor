package com.iocextractor.adapter.processing.camel.compile;

import com.iocextractor.adapter.processing.camel.contract.Condition;
import com.iocextractor.adapter.processing.camel.contract.FailureReference;
import com.iocextractor.adapter.processing.camel.contract.PlanDescriptor;
import com.iocextractor.adapter.processing.camel.contract.PlanSelection;
import com.iocextractor.adapter.processing.camel.contract.ViewOutcome;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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

    /** Evaluates only reached conditions, preserving branch and operand order. */
    public PlanSelection select(ViewLookup lookup) {
        return switch (mode) {
            case FIRST -> first(lookup);
            case ALL -> all(lookup);
            case EXCLUSIVE -> exclusive(lookup);
        };
    }

    private PlanSelection first(ViewLookup lookup) {
        for (BranchRule branch : branches) {
            Decision decision = branch.condition().evaluate(lookup);
            if (decision.status() == Decision.Status.MATCH) {
                return selection(PlanSelection.Status.MATCHED, List.of(branch.id()), List.of(), List.of());
            }
            if (decision.status() == Decision.Status.BLOCKED) {
                return blocked(branch.id(), decision.failure());
            }
        }
        return noMatch();
    }

    private PlanSelection all(ViewLookup lookup) {
        List<String> selected = new ArrayList<>();
        List<PlanSelection.BlockedBranch> blocked = new ArrayList<>();
        for (BranchRule branch : branches) {
            Decision decision = branch.condition().evaluate(lookup);
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

    private PlanSelection exclusive(ViewLookup lookup) {
        String firstMatch = null;
        for (BranchRule branch : branches) {
            Decision decision = branch.condition().evaluate(lookup);
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
            OperationCatalog.PredicateBinding predicate = predicates.get(leaf.predicate()).binding();
            return lookup -> {
                ViewOutcome view = lookup.resolve(leaf.view());
                if (view instanceof ViewOutcome.Unavailable unavailable) {
                    return Decision.blocked(unavailable.failure());
                }
                return predicate.matches(((ViewOutcome.Available) view).value(), leaf.arguments())
                        ? Decision.MATCH : Decision.NO_MATCH;
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
                                          ViewLookup lookup) {
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

    /** Resolves an original or derived view only when a reached predicate demands it. */
    @FunctionalInterface
    public interface ViewLookup {
        ViewOutcome resolve(String viewId);
    }

    @FunctionalInterface
    private interface CompiledCondition {
        Decision evaluate(ViewLookup lookup);
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
