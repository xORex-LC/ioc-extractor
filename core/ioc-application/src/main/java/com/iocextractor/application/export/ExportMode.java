package com.iocextractor.application.export;

/**
 * Materialization mode requested by an export profile.
 *
 * <p>Version 1 executes only {@link #COMPLETE}; {@link #APPEND} remains in the
 * storage-neutral model so configuration can reject it explicitly and a later
 * implementation can extend the use case without changing the manifest vocabulary.
 */
public enum ExportMode {
    COMPLETE,
    APPEND
}
