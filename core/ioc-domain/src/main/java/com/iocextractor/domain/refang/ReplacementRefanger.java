package com.iocextractor.domain.refang;

import java.util.List;
import java.util.ArrayList;
import java.util.Objects;

/**
 * Default {@link Refanger}: applies an ordered list of literal replacements.
 * Order matters — e.g. {@code hxxps→https} must precede {@code hxxp→http}.
 */
public final class ReplacementRefanger implements Refanger {

    private final List<RefangRule> rules;

    public ReplacementRefanger(List<RefangRule> rules) {
        this.rules = List.copyOf(rules);
    }

    @Override
    public RefangOutcome refang(String text) {
        Objects.requireNonNull(text, "text");
        if (text.isEmpty()) {
            return new RefangOutcome(text, List.of());
        }
        String out = text;
        var decisions = new ArrayList<RefangDecision>();
        for (int ruleIndex = 0; ruleIndex < rules.size(); ruleIndex++) {
            RefangRule rule = rules.get(ruleIndex);
            int replacements = countOccurrences(out, rule.from());
            if (replacements > 0) {
                decisions.add(new RefangDecision(ruleIndex, rule, replacements));
            }
            out = out.replace(rule.from(), rule.to());
        }
        return new RefangOutcome(out, decisions);
    }

    private int countOccurrences(String text, String token) {
        if (token.isEmpty()) {
            return text.length() + 1;
        }
        int count = 0;
        int offset = 0;
        while ((offset = text.indexOf(token, offset)) >= 0) {
            count++;
            offset += token.length();
        }
        return count;
    }
}
