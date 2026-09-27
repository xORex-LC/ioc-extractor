package com.iocextractor.adapter.processing.camel.contract;

import java.util.List;
import java.util.Objects;

/** Immutable technical descriptors; no IOC or Camel type crosses this contract. */
public record PlanDescriptor(String id, List<View> views, List<Branch> branches) {
    public PlanDescriptor {
        Objects.requireNonNull(id);
        views = List.copyOf(views);
        branches = List.copyOf(branches);
    }

    /** One named derived value with one registered operation and input view. */
    public record View(String id, String operation, String input) {
        public View {
            Objects.requireNonNull(id);
            Objects.requireNonNull(operation);
            Objects.requireNonNull(input);
        }
    }

    /** One configured destination and optional ordered eligibility condition. */
    public record Branch(String id, String destination, Condition eligibility) {
        public Branch {
            Objects.requireNonNull(id);
            Objects.requireNonNull(destination);
        }
    }
}
