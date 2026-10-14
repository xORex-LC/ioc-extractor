package com.iocextractor.domain.support;

import com.iocextractor.domain.extract.PatternEngine;
import com.iocextractor.domain.extract.Span;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Small deterministic fake for domain tests that need engine-neutral literal spans. */
public final class LiteralPatternEngine implements PatternEngine {

    @Override
    public String id() {
        return "literal-test";
    }

    @Override
    public Compiled compile(String token) {
        Objects.requireNonNull(token, "token");
        if (token.isEmpty()) {
            throw new IllegalArgumentException("test token must not be empty");
        }
        return new Compiled() {
            public List<Span> findAll(CharSequence text) { return LiteralPatternEngine.findAll(text.toString(), token); }
            public com.iocextractor.domain.extract.MatchCursor matches(CharSequence text) {
                return new com.iocextractor.domain.extract.MatchCursor() {
                    private int start = -1;
                    private int offset;
                    public boolean next() {
                        for (start = offset; start <= text.length() - token.length(); start++) {
                            int matched = 0;
                            while (matched < token.length() && text.charAt(start + matched) == token.charAt(matched)) { matched++; }
                            if (matched == token.length()) { offset = end(); return true; }
                        }
                        return false;
                    }
                    public int start() { return start; }
                    public int end() { return start + token.length(); }
                    public String value() { return token; }
                };
            }
        };
    }

    private static List<Span> findAll(String text, String token) {
        List<Span> spans = new ArrayList<>();
        int offset = 0;
        int start;
        while ((start = text.indexOf(token, offset)) >= 0) {
            int end = start + token.length();
            spans.add(new Span(start, end, token));
            offset = end;
        }
        return spans;
    }
}
