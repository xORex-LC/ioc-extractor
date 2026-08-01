package com.iocextractor.adapter.out.store.jdbc;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SqlTrustBoundaryTest {

    private static final String SQL_SHAPED_IDENTIFIER = "masks\"; DROP TABLE artifact_revision;--";

    @Test
    void rejects_sql_shaped_artifact_name_before_schema_generation() {
        assertThatThrownBy(() -> new DataframeArtifactSchema(SQL_SHAPED_IDENTIFIER, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid dataframe artifact name");
    }

    @Test
    void rejects_sql_shaped_column_name_before_schema_generation() {
        assertThatThrownBy(() -> new DataframeColumn(SQL_SHAPED_IDENTIFIER))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid dataframe column name");
    }

    @Test
    void rejects_sql_shaped_type_before_schema_generation() {
        assertThatThrownBy(() -> new DataframeColumn("mask", "TEXT); DROP TABLE artifact_revision;--"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported dataframe SQL type");
    }
}
