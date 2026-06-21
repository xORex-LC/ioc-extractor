package com.iocextractor.domain.extract;

import java.util.List;

/**
 * Port abstracting the regex engine. Default adapter is RE2/J (linear-time,
 * ReDoS-safe); {@code java.util.regex} is a drop-in alternative. Domain code
 * depends only on this interface, never on a concrete engine.
 *
 * <p>Patterns must therefore stay within the RE2 feature set
 * (no look-around / back-references) so either engine can run them.
 */
public interface PatternEngine {

    /** Engine id, e.g. {@code "re2j"} or {@code "jdk"}. */
    String id();

    /** Compile once, reuse for many documents. */
    Compiled compile(String regex);

    interface Compiled {
        /** All non-overlapping matches, in left-to-right order. */
        List<Span> findAll(CharSequence text);
    }
}
