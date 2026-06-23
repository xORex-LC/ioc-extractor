package com.iocextractor.adapter.out.store.jdbc;

/**
 * SQLite {@code PRAGMA synchronous} modes ordered by durability strength.
 */
public enum SqliteSynchronousMode {
    OFF(0),
    NORMAL(1),
    FULL(2),
    EXTRA(3);

    private final int pragmaValue;

    SqliteSynchronousMode(int pragmaValue) {
        this.pragmaValue = pragmaValue;
    }

    public int pragmaValue() {
        return pragmaValue;
    }
}
