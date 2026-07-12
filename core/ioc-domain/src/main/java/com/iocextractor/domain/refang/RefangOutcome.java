package com.iocextractor.domain.refang;

import java.util.List;
import java.util.Objects;

/**
 * Pure refang result containing transformed text and the rules that changed it.
 *
 * @param text transformed text
 * @param decisions applied-rule facts in declaration order
 */
public record RefangOutcome(String text, List<RefangDecision> decisions) {

    public RefangOutcome {
        Objects.requireNonNull(text, "text");
        decisions = List.copyOf(Objects.requireNonNull(decisions, "decisions"));
    }
}
