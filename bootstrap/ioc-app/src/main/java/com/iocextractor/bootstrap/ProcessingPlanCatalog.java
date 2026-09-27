package com.iocextractor.bootstrap;

import com.iocextractor.adapter.processing.camel.compile.OperationCatalog.PredicateRegistration;
import com.iocextractor.adapter.processing.camel.compile.PlanValidator;
import com.iocextractor.adapter.processing.camel.contract.PlanDescriptor;
import com.iocextractor.domain.feature.NetworkAddressParser;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** Compiles bounded IOC policy references into the existing technical Router contract. */
final class ProcessingPlanCatalog {
    private static final String ORIGINAL = "original";
    private static final String NETWORK_HOST = "network.host";
    private static final String RECOVER = "view.recover";
    private static final String TYPE_IN = "type-in";
    private static final int MAX_PLANS = 32;
    private static final Set<String> RECOVERABLE_REASONS = Arrays.stream(NetworkAddressParser.FailureReason.values())
            .map(reason -> reason.name().toLowerCase(java.util.Locale.ROOT).replace('_', '-'))
            .collect(Collectors.toUnmodifiableSet());

    private ProcessingPlanCatalog() { }

    /** A branch binds an artifact schema to one default and optional per-column view. */
    record BranchBinding(String artifact, String defaultView, Map<String, String> fieldViews) {
        BranchBinding {
            fieldViews = Map.copyOf(fieldViews);
        }
    }

    record CompiledPlan(PlanDescriptor router, Map<String, BranchBinding> bindings,
                        Set<String> classifiedViews) {
        CompiledPlan {
            bindings = Map.copyOf(bindings);
            classifiedViews = Set.copyOf(classifiedViews);
        }
    }

    static Map<String, CompiledPlan> compile(IocProperties properties, List<String> errors) {
        IocProcessingProperties configuration = properties.processing();
        List<IocProcessingProperties.Plan> plans = configuration.plans();
        if (plans == null) {
            errors.add("ioc.processing.plans must be a list");
            return Map.of();
        }
        if (plans.size() > MAX_PLANS) {
            errors.add("ioc.processing.plans exceeds the " + MAX_PLANS + " plan limit");
            return Map.of();
        }
        Map<String, IocProperties.Sink.Artifact> artifacts = enabledArtifacts(properties, errors);
        Map<String, CompiledPlan> compiled = new LinkedHashMap<>();
        Set<String> names = new HashSet<>();
        for (int index = 0; index < plans.size(); index++) {
            IocProcessingProperties.Plan plan = plans.get(index);
            String path = "ioc.processing.plans[" + index + "]";
            if (plan == null || blank(plan.name())) {
                errors.add(path + ".name is required");
                continue;
            }
            if (!names.add(plan.name())) {
                errors.add(path + ".name duplicates " + plan.name());
                continue;
            }
            int before = errors.size();
            CompiledPlan binding = compilePlan(plan, path, artifacts, errors);
            if (errors.size() == before) {
                compiled.put(plan.name(), binding);
            }
        }
        if (configuration.documentPlan() != null) {
            validateDocumentSelection(configuration, plans, compiled, artifacts.keySet(), errors);
        }
        return Map.copyOf(compiled);
    }

    private static Map<String, IocProperties.Sink.Artifact> enabledArtifacts(
            IocProperties properties, List<String> errors) {
        Map<String, IocProperties.Sink.Artifact> enabled = new LinkedHashMap<>();
        if (properties.sink() == null || properties.sink().artifacts() == null) {
            return enabled;
        }
        for (IocProperties.Sink.Artifact artifact : properties.sink().artifacts()) {
            if (artifact != null && artifact.enabled() && artifact.name() != null
                    && enabled.putIfAbsent(artifact.name(), artifact) != null) {
                errors.add("ioc.sink.artifacts has duplicate enabled name " + artifact.name());
            }
        }
        return enabled;
    }

    private static CompiledPlan compilePlan(IocProcessingProperties.Plan plan, String path,
                                            Map<String, IocProperties.Sink.Artifact> artifacts,
                                            List<String> errors) {
        int before = errors.size();
        List<PlanDescriptor.View> views = compileViews(plan.views(), path, errors);
        Set<String> knownViews = new LinkedHashSet<>();
        knownViews.add(ORIGINAL);
        views.forEach(view -> knownViews.add(view.id()));
        Set<String> classified = compileClassifications(plan.classifications(), knownViews, path, errors);
        IocProcessingProperties.Routing routing = plan.routing();
        if (routing == null || routing.mode() == null || routing.onUnmatched() == null
                || routing.onUnmatched().action() == null || routing.branches() == null) {
            errors.add(path + ".routing requires mode, on-unmatched.action and branches");
            return null;
        }
        Map<String, BranchBinding> bindings = new LinkedHashMap<>();
        ProcessingBranchCompiler branchCompiler = new ProcessingBranchCompiler(
                artifacts, knownViews, classified, bindings, errors);
        List<PlanDescriptor.Branch> branches = new ArrayList<>();
        for (int index = 0; index < routing.branches().size(); index++) {
            IocProcessingProperties.Branch branch = routing.branches().get(index);
            PlanDescriptor.Branch result = branchCompiler.compile(branch,
                    path + ".routing.branches[" + index + "]");
            if (result != null) {
                branches.add(result);
            }
        }
        PlanDescriptor.Branch defaultBranch = null;
        if (routing.defaultBranch() != null) {
            defaultBranch = branchCompiler.compile(routing.defaultBranch(),
                    path + ".routing.default-branch");
            if (routing.defaultBranch().eligibility() != null) {
                errors.add(path + ".routing.default-branch cannot have eligibility");
            }
        }
        validateOmissions(plan.omittedArtifacts(), artifacts.keySet(), bindings.values(), path, errors);
        if (errors.size() != before) {
            return null;
        }
        PlanDescriptor descriptor = new PlanDescriptor(plan.name(), views,
                new PlanDescriptor.Routing(PlanDescriptor.Mode.valueOf(routing.mode().name()),
                        branches, new PlanDescriptor.OnUnmatched(
                                PlanDescriptor.Action.valueOf(routing.onUnmatched().action().name()),
                                routing.onUnmatched().branch()), defaultBranch));
        try {
            PlanValidator.validate(descriptor, Set.of(NETWORK_HOST), artifacts.keySet(),
                    technicalPredicates(), RECOVERABLE_REASONS);
        } catch (IllegalArgumentException failure) {
            errors.add(path + ": " + failure.getMessage());
            return null;
        }
        return new CompiledPlan(descriptor, bindings, classified);
    }

    private static List<PlanDescriptor.View> compileViews(List<IocProcessingProperties.View> configured,
                                                           String path, List<String> errors) {
        List<PlanDescriptor.View> views = new ArrayList<>();
        if (configured == null) {
            errors.add(path + ".views must be a list");
            return views;
        }
        for (int index = 0; index < configured.size(); index++) {
            IocProcessingProperties.View view = configured.get(index);
            String at = path + ".views[" + index + "]";
            PlanDescriptor.View result = compileView(view, at, errors);
            if (result != null) {
                views.add(result);
            }
        }
        return views;
    }

    private static PlanDescriptor.View compileView(IocProcessingProperties.View view,
                                                    String path, List<String> errors) {
        if (view == null || blank(view.name()) || blank(view.operation()) || blank(view.input())) {
            errors.add(path + " requires name, operation and input");
            return null;
        }
        if (!RECOVER.equals(view.operation())) {
            if (!NETWORK_HOST.equals(view.operation()) || view.arguments() != null) {
                errors.add(path + " has unknown operation or unexpected arguments");
                return null;
            }
            return new PlanDescriptor.View(view.name(), view.operation(), view.input());
        }
        IocProcessingProperties.RecoveryArguments args = view.arguments();
        if (args == null || args.onReasons() == null || args.onReasons().isEmpty()
                || blank(args.useView()) || args.onReasons().contains(null)) {
            errors.add(path + ".arguments requires on-reasons and use-view");
            return null;
        }
        PlanDescriptor.Recovery recovery = new PlanDescriptor.Recovery(
                args.useView(), new HashSet<>(args.onReasons()));
        if (recovery.onReasons().size() != args.onReasons().size()) {
            errors.add(path + ".arguments.on-reasons contains duplicates");
        }
        return new PlanDescriptor.View(view.name(), view.operation(), view.input(), recovery);
    }

    private static Set<String> compileClassifications(List<IocProcessingProperties.Classification> configured,
                                                       Set<String> views, String path, List<String> errors) {
        Set<String> classified = new LinkedHashSet<>();
        if (configured == null) {
            errors.add(path + ".classifications must be a list");
            return classified;
        }
        for (int index = 0; index < configured.size(); index++) {
            IocProcessingProperties.Classification binding = configured.get(index);
            String at = path + ".classifications[" + index + "]";
            if (binding == null || !views.contains(binding.view())
                    || !"configured".equals(binding.policy())) {
                errors.add(at + " requires a declared view and policy configured");
            } else if (!classified.add(binding.view())) {
                errors.add(at + " duplicates classification for " + binding.view());
            }
        }
        return classified;
    }

    private static void validateOmissions(List<String> omissions, Set<String> enabled,
            java.util.Collection<BranchBinding> branches, String path, List<String> errors) {
        if (omissions == null) {
            return;
        }
        Set<String> routed = branches.stream().map(BranchBinding::artifact).collect(Collectors.toSet());
        Set<String> seen = new HashSet<>();
        for (String omission : omissions) {
            if (omission == null || !enabled.contains(omission) || !seen.add(omission)
                    || routed.contains(omission)) {
                errors.add(path + ".omitted-artifacts has unknown, duplicate or routed artifact " + omission);
            }
        }
    }

    private static void validateDocumentSelection(IocProcessingProperties configuration,
            List<IocProcessingProperties.Plan> plans, Map<String, CompiledPlan> compiled,
            Set<String> enabled, List<String> errors) {
        CompiledPlan selected = compiled.get(configuration.documentPlan());
        if (selected == null) {
            errors.add("ioc.processing.document-plan must reference a valid named plan");
            return;
        }
        IocProcessingProperties.Plan source = plans.stream().filter(Objects::nonNull)
                .filter(plan -> configuration.documentPlan().equals(plan.name())).findFirst().orElseThrow();
        Set<String> covered = selected.bindings().values().stream()
                .map(BranchBinding::artifact).collect(Collectors.toCollection(HashSet::new));
        if (source.omittedArtifacts() != null) {
            covered.addAll(source.omittedArtifacts());
        }
        for (String artifact : enabled) {
            if (!covered.contains(artifact)) {
                errors.add("ioc.processing.document-plan omits enabled artifact " + artifact
                        + " without omitted-artifacts acknowledgement");
            }
        }
    }

    private static Map<String, PredicateRegistration> technicalPredicates() {
        Map<String, PredicateRegistration> predicates = new HashMap<>();
        ConfigRegistryCatalog.classifyPredicateKeys().forEach(key ->
                predicates.put(key, new PredicateRegistration(Set.of(), (value, arguments) -> false)));
        predicates.put(TYPE_IN, new PredicateRegistration(Set.of("types"), (value, arguments) -> false));
        return predicates;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
