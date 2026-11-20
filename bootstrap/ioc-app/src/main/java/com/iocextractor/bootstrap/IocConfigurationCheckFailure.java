package com.iocextractor.bootstrap;

import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.validation.FieldError;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The small part of a failed check that can be printed without copying supplied values.
 * Original failures stay with the caller; this record never retains their messages or causes.
 */
record IocConfigurationCheckFailure(Kind kind, List<String> paths) {

    IocConfigurationCheckFailure {
        paths = List.copyOf(paths);
    }

    static IocConfigurationCheckFailure from(RuntimeException failure) {
        List<Throwable> causes = causes(failure);
        for (Throwable cause : causes) {
            if (cause instanceof BindValidationException validation) {
                return validationFailure(validation);
            }
        }
        BindException binding = null;
        for (Throwable cause : causes) {
            if (cause instanceof ConfigRegistryPreflight.RegistryConfigurationException) {
                return new IocConfigurationCheckFailure(Kind.REGISTRY, List.of());
            }
            if (cause instanceof BindException bind) {
                binding = bind;
            }
        }
        return binding == null
                ? new IocConfigurationCheckFailure(Kind.UNCLASSIFIED, List.of())
                : new IocConfigurationCheckFailure(Kind.BINDING,
                        List.of(IocConfigurationPropertyShape.diagnosticPath(binding.getName().toString())));
    }

    String description() {
        String locations = paths.isEmpty() ? "" : System.lineSeparator() + "- "
                + String.join(System.lineSeparator() + "- ", paths);
        return switch (kind) {
            case BINDING -> "CONFIG.BINDING_FAILED" + System.lineSeparator()
                    + "Configuration could not be bound at:" + locations + System.lineSeparator()
                    + "Check the documented type and supported tokens for these fields. "
                    + "Semantic and registry checks were not completed.";
            case VALIDATION -> "CONFIG.VALIDATION_FAILED" + System.lineSeparator()
                    + "Configuration constraints failed at:" + locations + System.lineSeparator()
                    + "Check the documented constraints for these fields. "
                    + "The complete configuration was not accepted; registry checks were not completed.";
            case REGISTRY -> "CONFIG.REGISTRY" + System.lineSeparator()
                    + "Configuration references or processing plans are invalid. "
                    + "Check providers, transforms, predicates, artifact references and named plans.";
            case UNCLASSIFIED -> "CONFIG.CHECK_FAILED" + System.lineSeparator()
                    + "Configuration validation could not complete. "
                    + "No successful validation is implied; investigate with controlled application diagnostics.";
        };
    }

    private static IocConfigurationCheckFailure validationFailure(BindValidationException failure) {
        var errors = failure.getValidationErrors();
        String root = errors.getName().toString();
        Set<String> paths = new LinkedHashSet<>();
        for (var error : errors.getAllErrors()) {
            String path = error instanceof FieldError field ? root + "." + field.getField() : root;
            paths.add(IocConfigurationPropertyShape.diagnosticPath(path));
        }
        if (paths.isEmpty()) {
            paths.add(IocConfigurationPropertyShape.diagnosticPath(root));
        }
        return new IocConfigurationCheckFailure(Kind.VALIDATION, List.copyOf(paths));
    }

    private static List<Throwable> causes(Throwable failure) {
        List<Throwable> result = new ArrayList<>();
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable current = failure; current != null && seen.add(current); current = current.getCause()) {
            result.add(current);
        }
        return result;
    }

    enum Kind {
        BINDING, VALIDATION, REGISTRY, UNCLASSIFIED
    }
}
