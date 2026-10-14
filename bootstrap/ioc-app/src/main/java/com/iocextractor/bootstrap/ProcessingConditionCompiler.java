package com.iocextractor.bootstrap;

import com.iocextractor.adapter.processing.camel.contract.Condition;
import com.iocextractor.domain.model.IndicatorType;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Admits one ordered boolean expression using registered, typed IOC leaves. */
final class ProcessingConditionCompiler {
    private static final String TYPE_IN = "type-in";
    private static final int MAX_DEPTH = 16;

    private final Set<String> views;
    private final Set<String> classifiedViews;
    private final List<String> errors;

    ProcessingConditionCompiler(Set<String> views, Set<String> classifiedViews,
                                List<String> errors) {
        this.views = views;
        this.classifiedViews = classifiedViews;
        this.errors = errors;
    }

    Condition compile(IocProcessingProperties.Condition source, String path, int depth) {
        if (source == null) {
            return null;
        }
        if (depth > MAX_DEPTH) {
            errors.add(path + " exceeds condition depth limit");
            return null;
        }
        if (shapeCount(source) != 1) {
            errors.add(path + " must have exactly one leaf, all, any or not shape");
            return null;
        }
        if (source.not() != null) {
            Condition child = compile(source.not(), path + ".not", depth + 1);
            return child == null ? null : new Condition.Not(child);
        }
        if (source.all() != null || source.any() != null) {
            return group(source, path, depth);
        }
        return leaf(source, path);
    }

    private static int shapeCount(IocProcessingProperties.Condition source) {
        return (source.predicate() != null || source.on() != null || source.arguments() != null ? 1 : 0)
                + (source.all() != null ? 1 : 0) + (source.any() != null ? 1 : 0)
                + (source.not() != null ? 1 : 0);
    }

    private Condition group(IocProcessingProperties.Condition source, String path, int depth) {
        List<IocProcessingProperties.Condition> inputs = source.all() != null ? source.all() : source.any();
        if (inputs.isEmpty()) {
            errors.add(path + " boolean group cannot be empty");
            return null;
        }
        List<Condition> children = new ArrayList<>();
        for (int index = 0; index < inputs.size(); index++) {
            if (inputs.get(index) == null) {
                errors.add(path + "[" + index + "] cannot be null");
                continue;
            }
            Condition child = compile(inputs.get(index), path + "[" + index + "]", depth + 1);
            if (child != null) {
                children.add(child);
            }
        }
        if (children.size() != inputs.size()) {
            return null;
        }
        return source.all() != null ? new Condition.All(children) : new Condition.Any(children);
    }

    private Condition leaf(IocProcessingProperties.Condition source, String path) {
        if (blank(source.on()) || blank(source.predicate()) || !views.contains(source.on())) {
            errors.add(path + " leaf requires a declared on view and predicate");
            return null;
        }
        if (TYPE_IN.equals(source.predicate())) {
            return typeIn(source, path);
        }
        if (!ConfigRegistryCatalog.classifyPredicateKeys().contains(source.predicate())
                || source.arguments() != null) {
            errors.add(path + " has unknown predicate or unexpected arguments");
            return null;
        }
        if (!classifiedViews.contains(source.on())) {
            errors.add(path + " requires classification for view " + source.on());
            return null;
        }
        return new Condition.Leaf(source.on(), source.predicate(), Map.of());
    }

    private Condition typeIn(IocProcessingProperties.Condition source, String path) {
        List<IndicatorType> types = source.arguments() == null ? null : source.arguments().types();
        if (types == null || types.isEmpty() || types.contains(null)
                || new HashSet<>(types).size() != types.size()) {
            errors.add(path + ".arguments.types requires distinct IOC types");
            return null;
        }
        return new Condition.Leaf(source.on(), TYPE_IN,
                Map.of("types", types.stream().map(Enum::name).collect(Collectors.joining(","))));
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
