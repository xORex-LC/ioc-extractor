package com.iocextractor.bootstrap;

import com.iocextractor.adapter.in.ingest.DurableDocumentDispatcher;
import com.iocextractor.adapter.out.store.jdbc.JdbcDocumentPreparationWorkspaceFactory;
import com.iocextractor.adapter.out.store.jdbc.JdbcSnapshotSliceReader;
import com.iocextractor.adapter.out.store.jdbc.JdbcWriterAdmission;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Bounded operational counters; export/publish lag remains in their existing ledger health views. */
final class DataProcessingCapacityHealthIndicator implements HealthIndicator {
    private final DurableDocumentDispatcher documents;
    private final JdbcWriterAdmission writers;
    private final JdbcSnapshotSliceReader readers;
    private final JdbcDocumentPreparationWorkspaceFactory workspaces;
    private final Path wal;

    DataProcessingCapacityHealthIndicator(DurableDocumentDispatcher documents, JdbcWriterAdmission writers,
            JdbcSnapshotSliceReader readers, JdbcDocumentPreparationWorkspaceFactory workspaces, String jdbcUrl) {
        this.documents = documents;
        this.writers = writers;
        this.readers = readers;
        this.workspaces = workspaces;
        wal = walPath(jdbcUrl);
    }

    static Path walPath(String jdbcUrl) {
        String prefix = "jdbc:sqlite:";
        if (!jdbcUrl.startsWith(prefix)) { return null; }
        String location = jdbcUrl.substring(prefix.length());
        if (location.equals(":memory:") || location.contains("mode=memory")) { return null; }
        if (location.startsWith("file:")) {
            location = java.net.URI.create(location).getSchemeSpecificPart().split("\\?", 2)[0];
        }
        return Path.of(Path.of(location).toAbsolutePath().normalize() + "-wal");
    }

    @Override public Health health() {
        try {
            var execution = documents.snapshot();
            var builder = execution.running() && execution.blocked() == 0 ? Health.up() : Health.down();
            return builder.withDetail("documentExecution", execution)
                    .withDetail("writerOperations", writers.snapshot())
                    .withDetail("queuedWriters", writers.queuedWriters())
                    .withDetail("activeSnapshotReaders", readers.activeReaders())
                    .withDetail("queuedSnapshotReaders", readers.queuedReaders())
                    .withDetail("preparationPressure", workspaces.pressure())
                    .withDetail("walBytes", wal != null && Files.exists(wal) ? Files.size(wal) : 0L).build();
        } catch (IOException | RuntimeException failure) {
            return Health.down().withException(failure).build();
        }
    }
}
