package com.iocextractor.adapter.processing.camel.contract;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable, ordered condition syntax; execution semantics belong to a later slice. */
public sealed interface Condition permits Condition.Leaf, Condition.All, Condition.Any, Condition.Not {
    /** References a registered predicate against a declared view. */
    record Leaf(String view, String predicate, Map<String, String> arguments) implements Condition {
        public Leaf {
            Objects.requireNonNull(view);
            Objects.requireNonNull(predicate);
            arguments = Map.copyOf(arguments);
        }
    }

    /** Every operand must match, evaluated in declaration order. */
    record All(List<Condition> children) implements Condition {
        public All { children = List.copyOf(children); }
    }

    /** Any operand may match, evaluated in declaration order. */
    record Any(List<Condition> children) implements Condition {
        public Any { children = List.copyOf(children); }
    }

    /** Inverts an available boolean result. */
    record Not(Condition child) implements Condition {
        public Not { Objects.requireNonNull(child); }
    }
}
