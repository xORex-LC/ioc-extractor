package com.iocextractor.adapter.out.store.jdbc;

import java.time.Duration;

/**
 * Effective SQLite PRAGMA set after correctness floors and tuning presets are
 * applied.
 */
public record SqlitePragmaSettings(
        String journalMode,
        String encoding,
        String autoVacuum,
        boolean foreignKeys,
        SqliteSynchronousMode synchronous,
        Duration busyTimeout,
        int cacheSize,
        long mmapSize,
        SqliteTempStore tempStore,
        int walAutocheckpoint,
        long journalSizeLimit) {
}
