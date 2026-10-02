package com.iocextractor.adapter.processing.camel.compile;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import org.apache.camel.Processor;

/** Startup-owned registry of local operation and destination implementations. */
public record OperationCatalog(Map<String, Processor> operations,
                               Map<String, Processor> destinations,
                               Map<String, PredicateRegistration> predicates,
                               Set<String> recoverableReasons) {
    public OperationCatalog {
        operations = Map.copyOf(operations);
        destinations = Map.copyOf(destinations);
        predicates = Map.copyOf(predicates);
        recoverableReasons = Set.copyOf(recoverableReasons);
    }

    public OperationCatalog(Map<String, Processor> operations,
                            Map<String, Processor> destinations,
                            Map<String, PredicateRegistration> predicates) {
        this(operations, destinations, predicates, Set.of());
    }

    /** One predicate implementation and its admitted argument names. */
    public record PredicateRegistration(Set<String> argumentNames, PredicateFactory factory,
                                        boolean acceptsAbsent) {
        public PredicateRegistration {
            argumentNames = Set.copyOf(argumentNames);
            Objects.requireNonNull(factory);
        }

        public PredicateRegistration(Set<String> argumentNames, PredicateBinding binding) {
            this(argumentNames, binding, false);
        }

        /** Adapts an argument-aware predicate without changing its invocation semantics. */
        public PredicateRegistration(Set<String> argumentNames, PredicateBinding binding,
                                     boolean acceptsAbsent) {
            this(argumentNames, adapt(binding), acceptsAbsent);
        }

        /** Creates a registration whose validated arguments are bound once per condition leaf. */
        public static PredicateRegistration parameterized(Set<String> argumentNames,
                                                          PredicateFactory factory) {
            return new PredicateRegistration(argumentNames, factory, false);
        }

        private static PredicateFactory adapt(PredicateBinding binding) {
            Objects.requireNonNull(binding);
            return arguments -> value -> binding.matches(value, arguments);
        }
    }

    /** Binds one admitted argument map into a reusable value predicate during compilation. */
    @FunctionalInterface
    public interface PredicateFactory {
        Predicate<Object> bind(Map<String, String> arguments);
    }

    /** Receives a value or an Absent marker only when registered for absence. */
    @FunctionalInterface
    public interface PredicateBinding {
        boolean matches(Object value, Map<String, String> arguments);
    }
}
