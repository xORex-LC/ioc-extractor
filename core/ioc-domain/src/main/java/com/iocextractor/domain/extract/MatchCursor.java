package com.iocextractor.domain.extract;

/** One matcher invocation; offsets refer to the entire supplied UTF-16 sequence. */
public interface MatchCursor {
    boolean next();
    int start();
    int end();
    /** Materializes only the current match, after the caller checks its length. */
    String value();
}
