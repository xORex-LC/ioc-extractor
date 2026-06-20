package com.iocextractor.adapter.out.regex;

import com.iocextractor.domain.extract.PatternEngine;
import com.iocextractor.domain.extract.Span;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Alternative {@link PatternEngine} backed by {@code java.util.regex}. Supports
 * the full Java regex feature set but is subject to backtracking (ReDoS risk),
 * so prefer {@link Re2jPatternEngine} for untrusted/large input.
 */
public final class JdkRegexPatternEngine implements PatternEngine {

    @Override
    public String id() {
        return "jdk";
    }

    @Override
    public Compiled compile(String regex) {
        Pattern pattern = Pattern.compile(regex);
        return text -> {
            Matcher matcher = pattern.matcher(text);
            List<Span> spans = new ArrayList<>();
            while (matcher.find()) {
                spans.add(new Span(matcher.start(), matcher.end(), matcher.group()));
            }
            return spans;
        };
    }
}
