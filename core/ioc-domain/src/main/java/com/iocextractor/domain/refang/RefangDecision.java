package com.iocextractor.domain.refang;

import java.util.Objects;

/**
 * Fact that one configured refang rule changed the source text.
 *
 * @param ruleIndex zero-based position in the ordered rule set
 * @param rule applied literal replacement
 * @param replacements number of non-overlapping replacements
 */
public record RefangDecision(int ruleIndex, RefangRule rule, int replacements) {

    public RefangDecision {
        if (ruleIndex < 0 || replacements < 1) {
            throw new IllegalArgumentException("Applied refang decision requires a valid index and count");
        }
        Objects.requireNonNull(rule, "rule");
    }
}
