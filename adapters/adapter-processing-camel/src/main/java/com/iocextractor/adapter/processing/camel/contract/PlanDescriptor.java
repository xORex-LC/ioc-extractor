package com.iocextractor.adapter.processing.camel.contract;

import java.util.List;
import java.util.Objects;

/** Immutable technical descriptors; no IOC or Camel type crosses this contract. */
public record PlanDescriptor(String id, List<View> views, Routing routing) {
    public PlanDescriptor {
        Objects.requireNonNull(id);
        views = List.copyOf(views);
        Objects.requireNonNull(routing);
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

    /** Ordered branch table, selection mode and an explicit no-match action. */
    public record Routing(Mode mode, List<Branch> branches,
                          OnUnmatched onUnmatched, Branch defaultBranch) {
        public Routing {
            Objects.requireNonNull(mode);
            branches = List.copyOf(branches);
            Objects.requireNonNull(onUnmatched);
        }
    }

    /** How many matching branches may be selected. */
    public enum Mode { FIRST, ALL, EXCLUSIVE }

    /** A default branch is used only for the ROUTE action. */
    public record OnUnmatched(Action action, String branch) {
        public OnUnmatched { Objects.requireNonNull(action); }
    }

    /** Explicit outcome when every evaluated branch is a conclusive no-match. */
    public enum Action { SKIP, REJECT, ROUTE }
}
