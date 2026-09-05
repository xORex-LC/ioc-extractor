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
        return text -> findAll(text.toString(), token);
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
