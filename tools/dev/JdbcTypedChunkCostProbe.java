package com.iocextractor.adapter.out.store.jdbc;

import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;

/** Isolated exact-driver chunk diagnostic; no whole-service capacity or UTC acceptance. */
public final class JdbcTypedChunkCostProbe {
    private JdbcTypedChunkCostProbe() { }

    /** Arguments: private database path and positive number of 256-row chunks. */
    public static void main(String[] arguments) throws Exception {
        Path database = Path.of(arguments[0]);
        int chunks = Integer.parseInt(arguments[1]);
        if (chunks < 1 || chunks > 1000) { throw new IllegalArgumentException("Use 1..1000 bounded chunks"); }
        var threads = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        if (!threads.isThreadAllocatedMemorySupported()) { throw new IllegalStateException("Allocation counter unavailable"); }
        threads.setThreadAllocatedMemoryEnabled(true);
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database)) {
            JdbcPublicationTable.execute(connection, "PRAGMA journal_mode=WAL");
            JdbcPublicationTable.execute(connection, "PRAGMA synchronous=FULL");
            JdbcPublicationTable.execute(connection, "PRAGMA cache_size=-512");
            JdbcPublicationTable.execute(connection, "PRAGMA foreign_keys=ON");
            JdbcPublicationTable.execute(connection, "CREATE TABLE chunk_payload(id INTEGER PRIMARY KEY,value TEXT,payload BLOB)");
            var table = JdbcPublicationTable.inspect(connection, "chunk_payload");
            var protocol = new JdbcPublicationProtocol();
            protocol.initialize(connection);
            JdbcTypedVersionTable.create(connection, table, false);
            JdbcPublicationTable.execute(connection, "DROP TABLE chunk_payload");
            JdbcTypedVersionTable.createView(connection, table, false);
            connection.setAutoCommit(false);
            var attempt = protocol.begin(connection, "chunk-cost", "fixed-fixture-v1",
                    new JdbcPublicationProtocol.Manifest(chunks, chunks * 256, Long.MAX_VALUE, Map.of("payload", 0L)));
            connection.commit();
            long allocated = threads.getThreadAllocatedBytes(Thread.currentThread().threadId());
            long start = System.nanoTime();
            long prepareNanos = 0;
            long appendNanos = 0;
            long commitNanos = 0;
            for (int ordinal = 0; ordinal < chunks; ordinal++) {
                long phase = System.nanoTime();
                var chunk = new JdbcTypedPublicationChunk(Set.of());
                for (int index = 0; index < 256; index++) {
                    int id = ordinal * 256 + index;
                    Object[] values = {id, id % 3 == 0 ? null : "value-" + id, new byte[]{0, -1, (byte) id}};
                    if (!chunk.add(new JdbcPrivateChanges.Effect(table, Arrays.asList(values), null, false, false), false)) {
                        throw new IllegalStateException("Fixture exceeded chunk bounds");
                    }
                }
                prepareNanos += System.nanoTime() - phase;
                phase = System.nanoTime();
                chunk.append(connection, protocol, attempt, "payload", ordinal);
                appendNanos += System.nanoTime() - phase;
                phase = System.nanoTime();
                connection.commit();
                commitNanos += System.nanoTime() - phase;
            }
            long elapsed = System.nanoTime() - start;
            long allocation = threads.getThreadAllocatedBytes(Thread.currentThread().threadId()) - allocated;
            protocol.seal(connection, attempt);
            protocol.publish(connection, attempt, 1_800_000_000_000L, "chunk-receipt");
            connection.commit();
            int count = 0;
            try (var statement = connection.prepareStatement("SELECT id,value,payload FROM chunk_payload ORDER BY id");
                 var rows = statement.executeQuery()) {
                while (rows.next()) {
                    String expected = count % 3 == 0 ? null : "value-" + count;
                    if (rows.getInt(1) != count || !java.util.Objects.equals(rows.getString(2), expected)
                            || !Arrays.equals(rows.getBytes(3), new byte[]{0, -1, (byte) count})) {
                        throw new IllegalStateException("Typed committed oracle failed at " + count);
                    }
                    count++;
                }
            }
            if (count != chunks * 256 || protocol.receipt(connection, attempt.unitId()).isEmpty()) {
                throw new IllegalStateException("Committed rows or receipt missing");
            }
            System.out.println("CHUNK_JSON={\"rows\":" + count + ",\"chunks\":" + chunks
                    + ",\"elapsed_nanos\":" + elapsed + ",\"prepare_nanos\":" + prepareNanos
                    + ",\"append_nanos\":" + appendNanos + ",\"commit_nanos\":" + commitNanos
                    + ",\"caller_allocated_bytes\":" + allocation + ",\"oracle\":\"PASS\"}");
        }
    }
}
