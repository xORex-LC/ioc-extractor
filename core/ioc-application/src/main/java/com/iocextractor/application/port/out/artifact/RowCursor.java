package com.iocextractor.application.port.out.artifact;

/** Invocation-owned cursor. Close on exhaustion, early exit and failure. */
public interface RowCursor<T> extends AutoCloseable {
    /** Advances to one row, or returns false at EOF. */
    boolean next();
    /** Returns the current row; valid only after a successful next call. */
    T value();
    @Override
    void close();
}
