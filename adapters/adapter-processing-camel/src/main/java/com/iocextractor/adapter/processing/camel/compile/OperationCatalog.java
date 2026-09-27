package com.iocextractor.adapter.processing.camel.compile;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.apache.camel.Processor;

/** Startup-owned registry of local operation and destination implementations. */
public record OperationCatalog(Map<String, Processor> operations,
                               Map<String, Processor> destinations,
                               Map<String, PredicateRegistration> predicates) {
    public OperationCatalog {
        operations = Map.copyOf(operations);
        destinations = Map.copyOf(destinations);
        predicates = Map.copyOf(predicates);
    }

    /** One predicate implementation and its admitted argument names. */
    public record PredicateRegistration(Set<String> argumentNames, PredicateBinding binding) {
        public PredicateRegistration {
            argumentNames = Set.copyOf(argumentNames);
            Objects.requireNonNull(binding);
        }
    }

    /** Called only for an available view; implementations must be thread-safe and pure. */
    @FunctionalInterface
    public interface PredicateBinding {
        boolean matches(Object value, Map<String, String> arguments);
    }
}
