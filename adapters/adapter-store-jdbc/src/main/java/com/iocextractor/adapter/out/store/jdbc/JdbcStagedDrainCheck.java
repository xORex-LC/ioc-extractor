package com.iocextractor.adapter.out.store.jdbc;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;

/** Existing coordination ledgers are the drain authority; an empty inbox does not prove a drained upgrade. */
final class JdbcStagedDrainCheck {
    private static final Map<String, String> SERVICE = Map.of(
            "ingestion_ledger", "status<>'SOURCE_ARCHIVED'",
            "ingest_run", "status NOT IN ('COMPLETED','FAILED')",
            "import_delivery", "state<>'TERMINAL'",
            "document_admission", "phase<>'TERMINAL' OR registration_finalized=0",
            "observation_admission_reference", "terminal_outcome IS NULL OR registration_finalized=0",
            "import_observation_reservation", "delivery_id IN (SELECT delivery_id FROM import_delivery WHERE state<>'TERMINAL')",
            "export_run", "status IN ('STARTED','STAGED','AVAILABLE')",
            "publish_ledger", "status IN ('PENDING','IN_PROGRESS','FAILED')");
    private JdbcStagedDrainCheck() { }

    static void requireDrained(Connection service) throws SQLException {
        for (var entry : new java.util.TreeMap<>(SERVICE).entrySet()) {
            try (var statement = service.createStatement(); var rows = statement.executeQuery("SELECT 1 FROM "
                    + entry.getKey() + " WHERE " + entry.getValue() + " LIMIT 1")) {
                if (rows.next()) { throw new SQLException("Staged migration requires drained " + entry.getKey()); }
            }
        }
    }
}
