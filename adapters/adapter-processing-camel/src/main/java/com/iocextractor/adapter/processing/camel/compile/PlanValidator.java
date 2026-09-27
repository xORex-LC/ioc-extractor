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
    static final String RECOVERY_OPERATION = "view.recover";

    private PlanValidator() { }

    /** Checks a plan and throws with a stable location on invalid input. */
    public static void validate(PlanDescriptor plan, Set<String> operations,
                                Set<String> destinations,
                                Map<String, PredicateRegistration> predicates,
                                Set<String> recoverableReasons) {
        checkId(plan.id(), "plan.id");
        if (plan.views().size() > MAX_VIEWS || plan.routing().branches().size() > MAX_BRANCHES) {
            throw new PlanAdmissionException(plan.id(), "view or branch limit exceeded");
        }
        Map<String, PlanDescriptor.View> views = validateViews(plan, operations,
                recoverableReasons);
        validateBranches(plan, views.keySet(), destinations, predicates);
    }

    private static Map<String, PlanDescriptor.View> validateViews(PlanDescriptor plan,
                                                                  Set<String> operations,
                                                                  Set<String> recoverableReasons) {
        Map<String, PlanDescriptor.View> views = registerViews(plan, operations, recoverableReasons);
        validateViewInputs(plan, views);
        validateViewGraph(plan, views);
        validateRecoveryGraph(plan, views);
        return views;
    }

    private static Map<String, PlanDescriptor.View> registerViews(PlanDescriptor plan,
                                                                  Set<String> operations,
                                                                  Set<String> recoverableReasons) {
        Map<String, PlanDescriptor.View> views = new HashMap<>();
        for (PlanDescriptor.View view : plan.views()) {
            String location = plan.id() + ".views." + view.id();
            checkId(view.id(), location);
            if ("original".equals(view.id()) || views.putIfAbsent(view.id(), view) != null) {
                throw new PlanAdmissionException(location, "duplicate or reserved view ID");
            }
            if (view.recovery() != null && !"original".equals(view.recovery().alternateView())
                    && !views.containsKey(view.recovery().alternateView())) {
                throw new PlanAdmissionException(location, "recovery alternate must precede recovery view");
            }
            if (view.recovery() != null) {
                validateRecovery(view, location, recoverableReasons);
            } else if (RECOVERY_OPERATION.equals(view.operation())) {
                throw new PlanAdmissionException(location, "recovery operation requires recovery edge");
            } else if (!operations.contains(view.operation())) {
                throw new PlanAdmissionException(location, "unregistered operation " + view.operation());
            }
        }
        return views;
    }

    private static void validateViewInputs(PlanDescriptor plan, Map<String, PlanDescriptor.View> views) {
        for (PlanDescriptor.View view : plan.views()) {
            if (!"original".equals(view.input()) && !views.containsKey(view.input())) {
                throw new PlanAdmissionException(plan.id() + ".views." + view.id(), "unknown input view");
            }
        }
    }

    private static void validateViewGraph(PlanDescriptor plan, Map<String, PlanDescriptor.View> views) {
        Map<String, Integer> visits = new HashMap<>();
        for (PlanDescriptor.View view : plan.views()) {
            visit(view.id(), views, visits, plan.id());
        }
    }

    private static void validateRecoveryGraph(PlanDescriptor plan, Map<String, PlanDescriptor.View> views) {
        for (PlanDescriptor.View view : plan.views()) {
            if (view.recovery() != null) {
                String location = plan.id() + ".views." + view.id();
                if (hasRecoveryAncestor(view.input(), views)
                        || hasRecoveryAncestor(view.recovery().alternateView(), views)) {
                    throw new PlanAdmissionException(location, "chained recovery is not supported");
                }
                if (dependsOn(view.recovery().alternateView(), view.input(), views)) {
                    throw new PlanAdmissionException(location,
                            "recovery alternate depends on primary view");
                }
            }
        }
    }

    private static boolean hasRecoveryAncestor(String viewId, Map<String, PlanDescriptor.View> views) {
        if ("original".equals(viewId)) {
            return false;
        }
        PlanDescriptor.View view = views.get(viewId);
        return view.recovery() != null || hasRecoveryAncestor(view.input(), views);
    }

    private static boolean dependsOn(String viewId, String ancestor,
                                     Map<String, PlanDescriptor.View> views) {
        if ("original".equals(viewId)) {
            return false;
        }
        return viewId.equals(ancestor) || dependsOn(views.get(viewId).input(), ancestor, views);
    }

    private static void validateRecovery(PlanDescriptor.View view, String location,
                                         Set<String> recoverableReasons) {
        if (!RECOVERY_OPERATION.equals(view.operation())) {
            throw new PlanAdmissionException(location, "recovery edge requires view.recover");
        }
        if ("original".equals(view.input())) {
            throw new PlanAdmissionException(location, "recovery primary must be a derived view");
        }
        if (view.input().equals(view.recovery().alternateView())) {
            throw new PlanAdmissionException(location, "recovery alternate must differ from primary");
        }
        if (view.recovery().onReasons().isEmpty()
                || view.recovery().onReasons().contains("*")
                || !recoverableReasons.containsAll(view.recovery().onReasons())) {
            throw new PlanAdmissionException(location, "unknown or empty recoverable reason");
        }
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
            validateRequiredViews(branch, views, location);
            if (branch.eligibility() != null) {
                nodes += checkCondition(branch.eligibility(), location, 1, views, predicates);
                if (nodes > MAX_NODES) {
                    throw new PlanAdmissionException(location, "condition node limit exceeded");
                }
            }
        }
        validateDefault(plan, branchIds, views, destinations);
    }

    private static void validateDefault(PlanDescriptor plan, Set<String> branchIds,
                                        Set<String> views,
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
        validateRequiredViews(fallback, views, location);
    }

    private static void validateRequiredViews(PlanDescriptor.Branch branch, Set<String> views,
                                              String location) {
        Set<String> distinct = new HashSet<>();
        for (String view : branch.requiredViews()) {
            if (!"original".equals(view) && !views.contains(view)) {
                throw new PlanAdmissionException(location, "unknown required view " + view);
            }
            if (!distinct.add(view)) {
                throw new PlanAdmissionException(location, "duplicate required view " + view);
            }
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
        PlanDescriptor.Recovery recovery = views.get(id).recovery();
        if (recovery != null && !"original".equals(recovery.alternateView())) {
            visit(recovery.alternateView(), views, visits, planId);
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
