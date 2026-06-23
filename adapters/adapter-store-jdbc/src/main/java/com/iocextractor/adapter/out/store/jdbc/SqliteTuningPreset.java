package com.iocextractor.adapter.out.store.jdbc;

import java.util.Locale;

/**
 * Host-tunable SQLite performance presets. Correctness PRAGMAs are not part of
 * the preset surface and are fixed by {@link SqlitePragmaPolicy}.
 */
public enum SqliteTuningPreset {
    LOW_MEMORY("low-memory", -2_000, 0L, SqliteTempStore.DEFAULT, 1_000, 8_388_608L),
    BALANCED("balanced", -16_000, 134_217_728L, SqliteTempStore.MEMORY, 1_000, 67_108_864L),
    HIGH_THROUGHPUT("high-throughput", -65_536, 536_870_912L, SqliteTempStore.MEMORY, 2_000, 268_435_456L);

    private final String configValue;
    private final int cacheSize;
    private final long mmapSize;
    private final SqliteTempStore tempStore;
    private final int walAutocheckpoint;
    private final long journalSizeLimit;

    SqliteTuningPreset(String configValue,
                       int cacheSize,
                       long mmapSize,
                       SqliteTempStore tempStore,
                       int walAutocheckpoint,
                       long journalSizeLimit) {
        this.configValue = configValue;
        this.cacheSize = cacheSize;
        this.mmapSize = mmapSize;
        this.tempStore = tempStore;
        this.walAutocheckpoint = walAutocheckpoint;
        this.journalSizeLimit = journalSizeLimit;
    }

    public static SqliteTuningPreset fromConfig(String value) {
        String normalized = value == null || value.isBlank()
                ? LOW_MEMORY.configValue
                : value.trim().toLowerCase(Locale.ROOT);
        for (SqliteTuningPreset preset : values()) {
            if (preset.configValue.equals(normalized)) {
                return preset;
            }
        }
        throw new IllegalArgumentException("Unknown SQLite tuning preset: " + value);
    }

    public String configValue() {
        return configValue;
    }

    public int cacheSize() {
        return cacheSize;
    }

    public long mmapSize() {
        return mmapSize;
    }

    public SqliteTempStore tempStore() {
        return tempStore;
    }

    public int walAutocheckpoint() {
        return walAutocheckpoint;
    }

    public long journalSizeLimit() {
        return journalSizeLimit;
    }
}
