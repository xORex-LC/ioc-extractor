package com.iocextractor.adapter.out.store.jdbc;

/**
 * SQLite {@code PRAGMA temp_store} values.
 */
public enum SqliteTempStore {
    DEFAULT(0),
    FILE(1),
    MEMORY(2);

    private final int pragmaValue;

    SqliteTempStore(int pragmaValue) {
        this.pragmaValue = pragmaValue;
    }

    public int pragmaValue() {
        return pragmaValue;
    }
}
