package com.iocextractor.adapter.out.regex;

import com.google.re2j.Matcher;
import com.google.re2j.Pattern;
import com.iocextractor.domain.extract.PatternEngine;


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
        return new CursorCompiledPattern(text -> {
            Matcher matcher = pattern.matcher(text);
            return new MatcherCursor(matcher::find, matcher::start, matcher::end, matcher::group);
        });
    }
}
