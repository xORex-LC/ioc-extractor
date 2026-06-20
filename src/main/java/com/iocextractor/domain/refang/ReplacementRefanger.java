package com.iocextractor.domain.refang;

import java.util.List;

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
    public String refang(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String out = text;
        for (RefangRule rule : rules) {
            out = out.replace(rule.from(), rule.to());
        }
        return out;
    }
}
