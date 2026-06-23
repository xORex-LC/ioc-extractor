package com.iocextractor.adapter.out.store.jdbc;

import java.time.Duration;

/**
 * Computes SQLite PRAGMA settings. Correctness-sensitive values are hard floors
 * owned by the adapter; host-dependent tuning comes from a named preset.
 */
public final class SqlitePragmaPolicy {

    public static final Duration BUSY_TIMEOUT_FLOOR = Duration.ofSeconds(5);
    public static final SqliteSynchronousMode SYNCHRONOUS_FLOOR = SqliteSynchronousMode.NORMAL;

    public SqlitePragmaSettings effective(String tuningPreset) {
        return effective(tuningPreset, BUSY_TIMEOUT_FLOOR, SYNCHRONOUS_FLOOR);
    }

    public SqlitePragmaSettings effective(String tuningPreset,
                                          Duration requestedBusyTimeout,
                                          SqliteSynchronousMode requestedSynchronous) {
        SqliteTuningPreset preset = SqliteTuningPreset.fromConfig(tuningPreset);
        Duration busyTimeout = clampBusyTimeout(requestedBusyTimeout);
        SqliteSynchronousMode synchronous = clampSynchronous(requestedSynchronous);
        return new SqlitePragmaSettings(
                "WAL",
                "UTF-8",
                "INCREMENTAL",
                true,
                synchronous,
                busyTimeout,
                preset.cacheSize(),
                preset.mmapSize(),
                preset.tempStore(),
                preset.walAutocheckpoint(),
                preset.journalSizeLimit());
    }

    private Duration clampBusyTimeout(Duration requested) {
        if (requested == null || requested.compareTo(BUSY_TIMEOUT_FLOOR) < 0) {
            return BUSY_TIMEOUT_FLOOR;
        }
        return requested;
    }

    private SqliteSynchronousMode clampSynchronous(SqliteSynchronousMode requested) {
        if (requested == null || requested.pragmaValue() < SYNCHRONOUS_FLOOR.pragmaValue()) {
            return SYNCHRONOUS_FLOOR;
        }
        return requested;
    }
}
