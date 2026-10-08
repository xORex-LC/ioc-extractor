package com.iocextractor.domain.refang;

/** Ordered literal rewriting over an owned text representation; no I/O authority in the domain. */
public interface TextRewrite {
    boolean isEmpty();
    /** Replaces left-to-right non-overlapping literals once and returns the replacement count. */
    int replace(RefangRule rule);
}
