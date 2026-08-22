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
    private static final String V2 = "com/iocextractor/adapter/out/store/jdbc/dataframe/v2__artifact_identity.sql";
    private static final String V3 = "com/iocextractor/adapter/out/store/jdbc/dataframe/v3__artifact_revision.sql";
    private static final String V4 =
            "com/iocextractor/adapter/out/store/jdbc/dataframe/v4__canonical_lifecycle_foundation.sql";
    private static final String V5 =
            "com/iocextractor/adapter/out/store/jdbc/dataframe/v5__stable_reusable_export_slots.sql";
    private static final String V6 =
            "com/iocextractor/adapter/out/store/jdbc/dataframe/v6__bounded_lifecycle_reconciliation_state.sql";

    private DataframeFormatMigrations() {
    }

    public static List<SqliteSchemaMigration> sqlite() {
        return List.of(
                new SqliteSchemaMigration(1, "dataframe format", resource(V1)),
                new SqliteSchemaMigration(2, "artifact identity", resource(V2)),
                new SqliteSchemaMigration(3, "artifact revision", resource(V3)),
                new SqliteSchemaMigration(4, "canonical lifecycle foundation", resource(V4)),
                new SqliteSchemaMigration(5, "stable reusable export slots", resource(V5)),
                new SqliteSchemaMigration(6, "bounded lifecycle reconciliation state", resource(V6)));
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
