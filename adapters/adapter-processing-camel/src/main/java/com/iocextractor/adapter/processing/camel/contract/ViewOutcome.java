package com.iocextractor.adapter.processing.camel.contract;

import java.util.Objects;

/** An operation's available value or expected unavailability; unexpected faults throw. */
public sealed interface ViewOutcome permits ViewOutcome.Available, ViewOutcome.Absent,
        ViewOutcome.Unavailable {
    /** A non-null value that predicates and downstream branches may consume. */
    record Available(Object value) implements ViewOutcome {
        public Available { Objects.requireNonNull(value); }
    }

    /** Optional input has no value; a registered presence predicate may inspect it. */
    record Absent(String originView) implements ViewOutcome {
        public Absent { Objects.requireNonNull(originView); }

        /** Operation implementations need not know their bound view ID. */
        public Absent() { this(""); }
    }

    /** A missing or failed required view with a stable causal reference. */
    record Unavailable(FailureReference failure) implements ViewOutcome {
        public Unavailable { Objects.requireNonNull(failure); }
    }
}
