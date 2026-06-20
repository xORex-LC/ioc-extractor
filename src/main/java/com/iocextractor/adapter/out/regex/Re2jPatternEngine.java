package com.iocextractor.adapter.out.regex;

import com.google.re2j.Matcher;
import com.google.re2j.Pattern;
import com.iocextractor.domain.extract.PatternEngine;
import com.iocextractor.domain.extract.Span;

import java.util.ArrayList;
import java.util.List;

/**
 * Default {@link PatternEngine} backed by Google RE2/J: linear-time matching,
 * immune to catastrophic backtracking — the safe choice for large, messy feeds.
 */
public final class Re2jPatternEngine implements PatternEngine {

    @Override
    public String id() {
        return "re2j";
    }

    @Override
    public Compiled compile(String regex) {
        Pattern pattern = Pattern.compile(regex);
        return text -> {
            Matcher matcher = pattern.matcher(text.toString());
            List<Span> spans = new ArrayList<>();
            while (matcher.find()) {
                spans.add(new Span(matcher.start(), matcher.end(), matcher.group()));
            }
            return spans;
        };
    }
}
