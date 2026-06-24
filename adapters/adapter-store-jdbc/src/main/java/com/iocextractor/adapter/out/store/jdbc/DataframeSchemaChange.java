package com.iocextractor.adapter.out.store.jdbc;

/**
 * One planned dataframe schema mutation.
 */
public record DataframeSchemaChange(Kind kind, String tableName, String columnName, String sql) {

    public enum Kind {
        CREATE_TABLE,
        ADD_COLUMN,
        CREATE_VIEW
    }
}
