package com.iocextractor.bootstrap;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Read-only exact-driver receipt family census; never changes a publication or retention deadline. */
final class ReceiptPayloadDiagnostics {
    private ReceiptPayloadDiagnostics() { }

    /** Complete ordered receipt replay through the real port, including each row's field positions. */
    static Map<String, Object> read(DataSource data,
            com.iocextractor.application.port.out.artifact.lifecycle.ConfirmationReceiptStore receipts,
            com.iocextractor.application.artifact.lifecycle.EffectiveTime asOf, int count) throws Exception {
        if (count < 1 || count > 50) { throw new IllegalArgumentException("Receipt read count must be 1..50"); }
        var keys = new ArrayList<List<String>>();
        try (var c = data.getConnection(); var s = c.prepareStatement("SELECT DISTINCT source_key,processing_policy_fingerprint"
                + " FROM confirmation_receipt WHERE state='COMPLETE' AND purge_after_ms>? ORDER BY source_key,processing_policy_fingerprint")) {
            s.setLong(1, asOf.value().toEpochMilli());
            try (var rows = s.executeQuery()) { while (rows.next()) { keys.add(List.of(rows.getString(1), rows.getString(2))); } }
        }
        if (keys.isEmpty()) { throw new IllegalStateException("No completed receipts to qualify"); }
        var samples = new ArrayList<Long>(count);
        var allocations = new ArrayList<Long>(count);
        var threads = (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
        long thread = Thread.currentThread().threadId();
        String expected = null;
        long total = 0;
        for (int iteration = 0; iteration < count; iteration++) {
            var checksum = java.security.MessageDigest.getInstance("SHA-256");
            long beforeBytes = threads.getThreadAllocatedBytes(thread);
            if (beforeBytes < 0) { throw new IllegalStateException("Receipt allocations unavailable"); }
            long started = System.nanoTime();
            long read = 0;
            for (var key : keys) {
                var receipt = receipts.findComplete(key.getFirst(), key.getLast(), asOf).orElseThrow();
                for (var artifact : receipt.artifacts()) {
                    checksum.update(artifact.artifactName().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    try (var rows = artifact.records().open()) {
                        while (rows.next()) {
                            var row = rows.value();
                            checksum.update((row.rowKey().value() + "\n" + row.preparedRow().template().values()
                                    + "\n" + new java.util.TreeMap<>(row.preparedRow().orderedFieldPositions()) + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
                            read++;
                        }
                    }
                }
            }
            samples.add(System.nanoTime() - started);
            allocations.add(threads.getThreadAllocatedBytes(thread) - beforeBytes);
            String digest = java.util.HexFormat.of().formatHex(checksum.digest());
            if (expected != null && (!expected.equals(digest) || total != read)) {
                throw new IllegalStateException("Receipt replay changed between scans");
            }
            expected = digest; total = read;
        }
        return Map.of("samplesNanos", samples, "allocatedThreadBytes", allocations, "rowsPerScan", total,
                "receiptCount", keys.size(), "payloadSha256", expected, "scope", "complete real-port receipt replay; lookup/count/cursor/positions included; post-bulk");
    }

    static Map<String, Object> inspect(DataSource data) throws SQLException {
        try (var connection = data.getConnection()) {
            connection.setAutoCommit(false);
            try {
                var pagesByTable = physicalPages(connection);
                var families = new ArrayList<Map<String, Object>>();
                try (var catalog = connection.createStatement(); var names = catalog.executeQuery(
                        "SELECT name FROM publication_native_schema ORDER BY name")) {
                    while (names.next()) {
                        String logical = names.getString(1);
                        String physical = "publication_version_" + logical;
                        var item = new LinkedHashMap<String, Object>();
                        item.put("family", logical);
                        item.put("receiptPayload", logical.endsWith("_receipt_rows")
                                || logical.equals("confirmation_receipt_field_position"));
                        try (var count = connection.createStatement(); var rows = count.executeQuery(
                                "SELECT COUNT(*) FROM \"" + physical.replace("\"", "\"\"") + "\"")) {
                            rows.next(); item.put("physicalRows", rows.getLong(1));
                        }
                        item.put("pageAndPayloadBytes", pagesByTable.getOrDefault(physical, Map.of()));
                        families.add(item);
                    }
                }
                var plans = new LinkedHashMap<String, List<String>>();
                for (var entry : Map.of("receiptRows", "SELECT ordinal,row_key FROM masks_receipt_rows WHERE receipt_id=? ORDER BY ordinal",
                        "fieldPositions", "SELECT field_name,occurrence_position FROM confirmation_receipt_field_position"
                                + " WHERE receipt_id=? AND artifact='masks' ORDER BY ordinal,field_name").entrySet()) {
                    var values = new ArrayList<String>();
                    try (var query = connection.prepareStatement("EXPLAIN QUERY PLAN " + entry.getValue())) {
                        query.setString(1, "diagnostic-plan");
                        try (var rows = query.executeQuery()) { while (rows.next()) { values.add(rows.getString(4)); } }
                    }
                    plans.put(entry.getKey(), values);
                }
                connection.rollback();
                return Map.of("families", families, "readPlans", plans, "scope", "pinned-driver dbstat table/index pages; retained rows, not insert timing");
            } catch (SQLException | RuntimeException | Error failure) {
                try { connection.rollback(); } catch (SQLException rollback) { failure.addSuppressed(rollback); }
                throw failure;
            }
        }
    }

    /** Inventory each btree once, avoiding a full virtual-table traversal for every family. */
    private static Map<String, Map<String, List<Long>>> physicalPages(java.sql.Connection connection) throws SQLException {
        var tablesByObject = new LinkedHashMap<String, String>();
        try (var statement = connection.createStatement(); var rows = statement.executeQuery(
                "SELECT name,tbl_name FROM sqlite_schema WHERE type IN('table','index')")) {
            while (rows.next()) { tablesByObject.put(rows.getString(1), rows.getString(2)); }
        }
        var pagesByTable = new LinkedHashMap<String, Map<String, List<Long>>>();
        try (var statement = connection.createStatement(); var rows = statement.executeQuery(
                "SELECT name,pgsize,payload FROM dbstat WHERE aggregate=TRUE ORDER BY name")) {
            while (rows.next()) {
                String name = rows.getString(1);
                String table = tablesByObject.get(name);
                if (table != null) {
                    pagesByTable.computeIfAbsent(table, ignored -> new LinkedHashMap<>())
                            .put(name, List.of(rows.getLong(2), rows.getLong(3)));
                }
            }
        }
        return pagesByTable;
    }
}
