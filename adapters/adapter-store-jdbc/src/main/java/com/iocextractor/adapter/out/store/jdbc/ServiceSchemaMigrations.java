package com.iocextractor.adapter.out.store.jdbc;

import com.iocextractor.common.IocExtractorException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Versioned schema migrations for the service storage context.
 */
public final class ServiceSchemaMigrations {

    private static final String V1 = "com/iocextractor/adapter/out/store/jdbc/service/v1__service_schema.sql";
    private static final String V2 = "com/iocextractor/adapter/out/store/jdbc/service/v2__run_ledger.sql";
    private static final String V3 = "com/iocextractor/adapter/out/store/jdbc/service/v3__drop_ingestion_partition.sql";
    private static final String V4 = "com/iocextractor/adapter/out/store/jdbc/service/v4__ingest_run_ledger.sql";

    private ServiceSchemaMigrations() {
    }

    public static List<SqliteSchemaMigration> sqlite() {
        return List.of(
                new SqliteSchemaMigration(1, "service schema", resource(V1)),
                new SqliteSchemaMigration(2, "run ledger", resource(V2)),
                new SqliteSchemaMigration(3, "drop ingestion partitions", resource(V3)),
                new SqliteSchemaMigration(4, "ingest run ledger", resource(V4)));
    }

    private static String resource(String name) {
        ClassLoader loader = ServiceSchemaMigrations.class.getClassLoader();
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
