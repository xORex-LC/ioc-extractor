package com.iocextractor.bootstrap;

import com.iocextractor.adapter.processing.camel.contract.Condition;
import com.iocextractor.adapter.processing.camel.contract.PlanDescriptor;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Resolves one IOC destination against the existing artifact column catalog. */
final class ProcessingBranchCompiler {
    private final Map<String, IocProperties.Sink.Artifact> artifacts;
    private final Set<String> views;
    private final Set<String> classified;
    private final Map<String, ProcessingPlanCatalog.BranchBinding> bindings;
    private final List<String> errors;

    ProcessingBranchCompiler(Map<String, IocProperties.Sink.Artifact> artifacts,
                             Set<String> views, Set<String> classified,
                             Map<String, ProcessingPlanCatalog.BranchBinding> bindings,
                             List<String> errors) {
        this.artifacts = artifacts;
        this.views = views;
        this.classified = classified;
        this.bindings = bindings;
        this.errors = errors;
    }

    PlanDescriptor.Branch compile(IocProcessingProperties.Branch branch, String path) {
        if (branch == null || blank(branch.id()) || blank(branch.artifact())
                || blank(branch.defaultView())) {
            errors.add(path + " requires id, artifact and default-view");
            return null;
        }
        IocProperties.Sink.Artifact artifact = artifacts.get(branch.artifact());
        if (artifact == null) {
            errors.add(path + ".artifact must name an enabled artifact");
            return null;
        }
        int before = errors.size();
        ResolvedFields fields = resolveFields(branch, artifact, path);
        for (String view : fields.requiredViews()) {
            if (!classified.contains(view)) {
                errors.add(path + " requires classification for view " + view);
            }
        }
        if (errors.size() != before) {
            return null;
        }
        if (bindings.putIfAbsent(branch.id(), new ProcessingPlanCatalog.BranchBinding(
                branch.artifact(), branch.defaultView(), fields.fieldViews())) != null) {
            errors.add(path + ".id duplicates " + branch.id());
        }
        Condition condition = new ProcessingConditionCompiler(views, classified, errors)
                .compile(branch.eligibility(), path + ".eligibility", 1);
        return new PlanDescriptor.Branch(branch.id(), branch.artifact(), condition,
                List.copyOf(fields.requiredViews()));
    }

    private ResolvedFields resolveFields(IocProcessingProperties.Branch branch,
                                         IocProperties.Sink.Artifact artifact, String path) {
        Set<String> required = new LinkedHashSet<>();
        required.add(branch.defaultView());
        if (!views.contains(branch.defaultView())) {
            errors.add(path + ".default-view is unknown");
        }
        Map<String, String> effective = new LinkedHashMap<>();
        if (branch.fieldViews() == null) {
            return new ResolvedFields(required, effective);
        }
        for (Map.Entry<String, String> override : branch.fieldViews().entrySet()) {
            resolveOverride(artifact, override, path, required, effective);
        }
        return new ResolvedFields(required, effective);
    }

    private void resolveOverride(IocProperties.Sink.Artifact artifact,
                                 Map.Entry<String, String> override, String path,
                                 Set<String> required, Map<String, String> effective) {
        String fieldPath = path + ".field-views." + override.getKey();
        IocProperties.Sink.Artifact.Column column = column(artifact, override.getKey());
        if (column == null || "id".equals(column.from()) || "source.label".equals(column.from())) {
            errors.add(fieldPath + " must name a non-context-owned column");
        }
        if (blank(override.getValue()) || !views.contains(override.getValue())) {
            errors.add(fieldPath + " references unknown view");
        } else if (column != null && usesView(column)) {
            required.add(override.getValue());
            effective.put(override.getKey(), override.getValue());
        }
    }

    private static IocProperties.Sink.Artifact.Column column(IocProperties.Sink.Artifact artifact,
                                                              String name) {
        if (artifact.columns() == null) {
            return null;
        }
        return artifact.columns().stream().filter(Objects::nonNull)
                .filter(candidate -> Objects.equals(candidate.name(), name))
                .findFirst().orElse(null);
    }

    private static boolean usesView(IocProperties.Sink.Artifact.Column column) {
        return !"const".equals(column.from()) || column.whenType() != null
                || column.whenTypes() != null || column.when() != null;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private record ResolvedFields(Set<String> requiredViews, Map<String, String> fieldViews) { }
}
