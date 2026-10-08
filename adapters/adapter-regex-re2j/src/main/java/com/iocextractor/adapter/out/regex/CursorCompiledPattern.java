package com.iocextractor.adapter.out.regex;

import com.iocextractor.domain.extract.MatchCursor;
import com.iocextractor.domain.extract.PatternEngine;
import com.iocextractor.domain.extract.Span;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/** One cursor implementation serves both lazy production matching and the finite batch API. */
final class CursorCompiledPattern implements PatternEngine.Compiled {
    private final Function<CharSequence, MatchCursor> matcher;

    CursorCompiledPattern(Function<CharSequence, MatchCursor> matcher) { this.matcher = matcher; }

    public MatchCursor matches(CharSequence text) { return matcher.apply(text); }

    public List<Span> findAll(CharSequence text) {
        var cursor = matches(text);
        List<Span> spans = new ArrayList<>();
        while (cursor.next()) { spans.add(new Span(cursor.start(), cursor.end(), cursor.value())); }
        return spans;
    }
}
