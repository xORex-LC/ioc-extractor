package com.iocextractor.adapter.processing.camel.compile;

import com.iocextractor.adapter.processing.camel.contract.Condition;
import com.iocextractor.adapter.processing.camel.contract.PlanDescriptor;
import com.iocextractor.adapter.processing.camel.compile.OperationCatalog.PredicateRegistration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Validates structural limits and registered references before route generation. */
public final class PlanValidator {
    private static final Pattern ID = Pattern.compile("[a-z][a-z0-9_-]{0,63}");
    private static final int MAX_VIEWS = 64;
    private static final int MAX_BRANCHES = 64;
    private static final int MAX_NODES = 256;
    private static final int MAX_DEPTH = 16;

    private PlanValidator() { }

    /** Checks a plan and throws with a stable location on invalid input. */
    public static void validate(PlanDescriptor plan, Set<String> operations,
                                Set<String> destinations,
                                Map<String, PredicateRegistration> predicates) {
        checkId(plan.id(), "plan.id");
        if (plan.views().size() > MAX_VIEWS || plan.routing().branches().size() > MAX_BRANCHES) {
            throw new PlanAdmissionException(plan.id(), "view or branch limit exceeded");
        }
        Map<String, PlanDescriptor.View> views = validateViews(plan, operations);
        validateBranches(plan, views.keySet(), destinations, predicates);
    }

    private static Map<String, PlanDescriptor.View> validateViews(PlanDescriptor plan,
                                                                  Set<String> operations) {
        Map<String, PlanDescriptor.View> views = new HashMap<>();
        for (PlanDescriptor.View view : plan.views()) {
            String location = plan.id() + ".views." + view.id();
            checkId(view.id(), location);
            if ("original".equals(view.id()) || views.putIfAbsent(view.id(), view) != null) {
                throw new PlanAdmissionException(location, "duplicate or reserved view ID");
            }
            if (!operations.contains(view.operation())) {
                throw new PlanAdmissionException(location, "unregistered operation " + view.operation());
            }
        }
        for (PlanDescriptor.View view : plan.views()) {
            if (!"original".equals(view.input()) && !views.containsKey(view.input())) {
                throw new PlanAdmissionException(plan.id() + ".views." + view.id(), "unknown input view");
            }
        }
        Map<String, Integer> visits = new HashMap<>();
        for (PlanDescriptor.View view : plan.views()) {
            visit(view.id(), views, visits, plan.id());
        }
        return views;
    }

    private static void validateBranches(PlanDescriptor plan, Set<String> views,
                                         Set<String> destinations,
                                         Map<String, PredicateRegistration> predicates) {
        PlanDescriptor.Routing routing = plan.routing();
        if (routing.branches().isEmpty() && routing.onUnmatched().action() != PlanDescriptor.Action.ROUTE) {
            throw new PlanAdmissionException(plan.id() + ".routing", "empty branches require a default route");
        }
        Set<String> branchIds = new HashSet<>();
        int nodes = 0;
        for (PlanDescriptor.Branch branch : routing.branches()) {
            String location = plan.id() + ".branches." + branch.id();
            checkId(branch.id(), location);
            if (!branchIds.add(branch.id())) {
                throw new PlanAdmissionException(location, "duplicate branch ID");
            }
            if (!destinations.contains(branch.destination())) {
                throw new PlanAdmissionException(location, "unregistered destination " + branch.destination());
            }
            if (branch.eligibility() != null) {
                nodes += checkCondition(branch.eligibility(), location, 1, views, predicates);
                if (nodes > MAX_NODES) {
                    throw new PlanAdmissionException(location, "condition node limit exceeded");
                }
            }
        }
        validateDefault(plan, branchIds, destinations);
    }

    private static void validateDefault(PlanDescriptor plan, Set<String> branchIds,
                                        Set<String> destinations) {
        PlanDescriptor.Routing routing = plan.routing();
        PlanDescriptor.Branch fallback = routing.defaultBranch();
        String location = plan.id() + ".routing.on-unmatched";
        if (routing.onUnmatched().action() != PlanDescriptor.Action.ROUTE) {
            if (fallback != null || routing.onUnmatched().branch() != null) {
                throw new PlanAdmissionException(location, "default branch requires route action");
            }
            return;
        }
        if (fallback == null || !fallback.id().equals(routing.onUnmatched().branch())) {
            throw new PlanAdmissionException(location, "route action requires matching default branch");
        }
        checkId(fallback.id(), location);
        if (branchIds.contains(fallback.id())) {
            throw new PlanAdmissionException(location, "duplicate default branch ID");
        }
        if (fallback.eligibility() != null) {
            throw new PlanAdmissionException(location, "default branch cannot have eligibility");
        }
        if (!destinations.contains(fallback.destination())) {
            throw new PlanAdmissionException(location, "unregistered destination " + fallback.destination());
        }
    }

    private static void visit(String id, Map<String, PlanDescriptor.View> views,
                              Map<String, Integer> visits, String planId) {
        Integer state = visits.get(id);
        if (state != null) {
            if (state == 1) {
                throw new PlanAdmissionException(planId + ".views." + id, "view dependency cycle");
            }
            return;
        }
        visits.put(id, 1);
        String input = views.get(id).input();
        if (!"original".equals(input)) {
            visit(input, views, visits, planId);
        }
        visits.put(id, 2);
    }

    private static int checkCondition(Condition condition, String location, int depth,
                                      Set<String> views,
                                      Map<String, PredicateRegistration> predicates) {
        if (depth > MAX_DEPTH) {
            throw new PlanAdmissionException(location, "condition depth limit exceeded");
        }
        if (condition instanceof Condition.Leaf leaf) {
            checkLeaf(leaf, location, views, predicates);
            return 1;
        }
        if (condition instanceof Condition.Not not) {
            return 1 + checkCondition(not.child(), location, depth + 1, views, predicates);
        }
        var children = condition instanceof Condition.All all ? all.children()
                : ((Condition.Any) condition).children();
        return checkGroup(children, location, depth, views, predicates);
    }

    private static void checkLeaf(Condition.Leaf leaf, String location, Set<String> views,
                                  Map<String, PredicateRegistration> predicates) {
        if (!"original".equals(leaf.view()) && !views.contains(leaf.view())) {
            throw new PlanAdmissionException(location, "unknown condition view " + leaf.view());
        }
        PredicateRegistration predicate = predicates.get(leaf.predicate());
        if (predicate == null) {
            throw new PlanAdmissionException(location, "unregistered predicate " + leaf.predicate());
        }
        if (!predicate.argumentNames().containsAll(leaf.arguments().keySet())) {
            throw new PlanAdmissionException(location, "unknown predicate argument");
        }
    }

    private static int checkGroup(java.util.List<Condition> children, String location, int depth,
                                  Set<String> views,
                                  Map<String, PredicateRegistration> predicates) {
        if (children.isEmpty()) {
            throw new PlanAdmissionException(location, "empty condition group");
        }
        int nodes = 1;
        for (Condition child : children) {
            nodes += checkCondition(child, location, depth + 1, views, predicates);
            if (nodes > MAX_NODES) {
                throw new PlanAdmissionException(location, "condition node limit exceeded");
            }
        }
        return nodes;
    }

    private static void checkId(String id, String location) {
        if (!ID.matcher(id).matches()) {
            throw new PlanAdmissionException(location, "invalid ID");
        }
    }
}
