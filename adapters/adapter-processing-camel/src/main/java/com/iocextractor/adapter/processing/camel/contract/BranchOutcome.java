package com.iocextractor.adapter.processing.camel.contract;

import java.util.Objects;

/** A selected destination produces at most one candidate or an explicit non-row result. */
public sealed interface BranchOutcome permits BranchOutcome.Prepared,
        BranchOutcome.Filtered, BranchOutcome.Unavailable {
    /** The candidate still needs application-owned validation, identity and checkpoint. */
    record Prepared(Object candidate) implements BranchOutcome {
        public Prepared { Objects.requireNonNull(candidate); }
    }

    /** Intentional non-row output after a selected branch's own gates. */
    record Filtered() implements BranchOutcome { }

    /** An expected destination failure; it does not cause route reselection. */
    record Unavailable(FailureReference failure, Object evidence) implements BranchOutcome {
        public Unavailable { Objects.requireNonNull(failure); }

        public Unavailable(FailureReference failure) { this(failure, null); }
    }
}
