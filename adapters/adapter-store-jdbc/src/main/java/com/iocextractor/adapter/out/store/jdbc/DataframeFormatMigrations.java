package com.iocextractor.adapter.out.store.jdbc;

import com.iocextractor.common.IocExtractorException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Versioned migrations for the stable dataframe format tables. Per-artifact
 * business columns are reconciled separately from configuration.
 */
public final class DataframeFormatMigrations {

    private static final String V1 = "com/iocextractor/adapter/out/store/jdbc/dataframe/v1__dataframe_format.sql";

    private DataframeFormatMigrations() {
    }

    public static List<SqliteSchemaMigration> sqlite() {
        return List.of(new SqliteSchemaMigration(1, "dataframe format", resource(V1)));
    }

    private static String resource(String name) {
        ClassLoader loader = DataframeFormatMigrations.class.getClassLoader();
        try (var input = loader.getResourceAsStream(name)) {
            if (input == null) {
                throw new IocExtractorException("Missing schema migration resource: " + name);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IocExtractorException("Failed to read schema migration resource: " + name, e);
        }
    }
}
