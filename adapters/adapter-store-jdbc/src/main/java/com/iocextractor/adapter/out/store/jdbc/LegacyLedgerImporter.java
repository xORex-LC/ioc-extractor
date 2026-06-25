package com.iocextractor.adapter.out.store.jdbc;

import com.iocextractor.application.ingest.IngestionRecord;
import com.iocextractor.application.ingest.IngestionStatus;
import com.iocextractor.application.ingest.SourceKey;
import com.iocextractor.application.ingest.SourceUnit;
import com.iocextractor.application.port.out.ingest.IngestionLedger;
import com.iocextractor.common.IocExtractorException;
import com.iocextractor.diagnostics.DiagnosticFactory;
import com.iocextractor.diagnostics.codes.StorageDiagnosticCodes;
import com.iocextractor.diagnostics.sink.DiagnosticSink;
import com.iocextractor.diagnostics.sink.NoopDiagnosticSink;
import com.iocextractor.observability.EventAction;
import com.iocextractor.observability.EventOutcome;
import com.iocextractor.observability.LogField;
import com.iocextractor.observability.logging.LogEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Properties;

/**
 * Imports the legacy file-backed ingestion ledger into the JDBC service ledger.
 * The legacy file format stays at the adapter edge; durable checkpoints are
 * replayed through the storage-neutral {@link IngestionLedger} port.
 */
public final class LegacyLedgerImporter {

    private static final Logger LOGGER = LoggerFactory.getLogger(LegacyLedgerImporter.class);
    private static final String STATUS_IN_PROGRESS = "IN_PROGRESS";
    private static final String STATUS_COMPLETED = "COMPLETED";
    private static final String STATUS_FAILED = "FAILED";

    private final Path legacyDir;
    private final IngestionLedger ledger;
    private final JdbcClient jdbc;
    private final DiagnosticSink diagnosticSink;
    private final DiagnosticFactory diagnosticFactory;
    private final Clock clock;

    public LegacyLedgerImporter(Path legacyDir,
                                IngestionLedger ledger,
                                DataSource dataSource,
                                Clock clock) {
        this(legacyDir, ledger, dataSource, NoopDiagnosticSink.INSTANCE, new DiagnosticFactory(clock), clock);
    }

    public LegacyLedgerImporter(Path legacyDir,
                                IngestionLedger ledger,
                                DataSource dataSource,
                                DiagnosticSink diagnosticSink,
                                DiagnosticFactory diagnosticFactory,
                                Clock clock) {
        this.legacyDir = Objects.requireNonNull(legacyDir, "legacyDir");
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        this.jdbc = JdbcClient.create(Objects.requireNonNull(dataSource, "dataSource"));
        this.diagnosticSink = Objects.requireNonNull(diagnosticSink, "diagnosticSink");
        this.diagnosticFactory = Objects.requireNonNull(diagnosticFactory, "diagnosticFactory");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public LegacyLedgerImportSummary importAll() {
        if (!Files.isDirectory(legacyDir)) {
            return new LegacyLedgerImportSummary(0, 0, 0, 0);
        }
        List<Path> files = legacyFiles();
        int imported = 0;
        int skipped = 0;
        int failed = 0;
        for (Path file : files) {
            String name = file.getFileName().toString();
            // Best-effort: a single unreadable/corrupt legacy file is recorded FAILED and
            // skipped, not allowed to abort daemon startup. Re-runs retry it idempotently.
            try {
                String checksum = checksum(file);
                if (alreadyCompleted(name, checksum)) {
                    skipped++;
                    emitReplaySkipped(name, file);
                    continue;
                }
                markStarted(name, file, checksum);
                replay(read(file));
                markCompleted(name);
                imported++;
                logImported(name, file);
            } catch (RuntimeException e) {
                markPartial(name, file, e);
                failed++;
            }
        }
        LegacyLedgerImportSummary summary = new LegacyLedgerImportSummary(files.size(), imported, skipped, failed);
        logSummary(summary);
        return summary;
    }

    private List<Path> legacyFiles() {
        try (var stream = Files.list(legacyDir)) {
            return stream
                    .filter(path -> path.getFileName().toString().endsWith(".properties"))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new IocExtractorException("Failed to list legacy ingestion ledger: " + legacyDir, e);
        }
    }

    private boolean alreadyCompleted(String name, String checksum) {
        return jdbc.sql("""
                        SELECT COUNT(*)
                        FROM legacy_imports
                        WHERE name = :name
                          AND checksum = :checksum
                          AND status = :status
                        """)
                .param("name", name)
                .param("checksum", checksum)
                .param("status", STATUS_COMPLETED)
                .query(Integer.class)
                .single() > 0;
    }

    private void markStarted(String name, Path file, String checksum) {
        jdbc.sql("""
                        INSERT INTO legacy_imports (name, source_path, checksum, status, completed_at)
                        VALUES (:name, :source_path, :checksum, :status, NULL)
                        ON CONFLICT(name) DO UPDATE SET
                            source_path = excluded.source_path,
                            checksum = excluded.checksum,
                            status = excluded.status,
                            completed_at = NULL
                        """)
                .param("name", name)
                .param("source_path", file.toString())
                .param("checksum", checksum)
                .param("status", STATUS_IN_PROGRESS)
                .update();
    }

    private void markCompleted(String name) {
        jdbc.sql("""
                        UPDATE legacy_imports
                        SET status = :status,
                            completed_at = :completed_at
                        WHERE name = :name
                        """)
                .param("status", STATUS_COMPLETED)
                .param("completed_at", Instant.now(clock).toString())
                .param("name", name)
                .update();
    }

    private void markPartial(String name, Path file, RuntimeException e) {
        jdbc.sql("""
                        UPDATE legacy_imports
                        SET status = :status,
                            completed_at = NULL
                        WHERE name = :name
                        """)
                .param("status", STATUS_FAILED)
                .param("name", name)
                .update();
        diagnosticSink.emit(diagnosticFactory.create(StorageDiagnosticCodes.IMPORT_PARTIAL)
                .with("importName", name)
                .with("sourcePath", file.toString())
                .with("reason", e.getMessage())
                .cause(e)
                .build());
        LogEvents.warn(LOGGER)
                .action(EventAction.LEDGER_IMPORT)
                .outcome(EventOutcome.FAILURE)
                .field(LogField.FILE_PATH, file.toString())
                .message("legacy ingestion ledger import stopped before completion")
                .log(e);
    }

    private void replay(IngestionRecord record) {
        ledger.markClaimed(new SourceUnit(record.key(), record.originalPath(), record.processingPath(),
                detectedAt(record)));
        switch (record.status()) {
            case CLAIMED -> {
            }
            case SOURCE_ARCHIVED -> ledger.markSourceArchived(record.key(), requireArchivedPath(record));
            case FAILED -> ledger.markFailed(record.key(), record.reason());
        }
    }

    private Path requireArchivedPath(IngestionRecord record) {
        if (record.archivedPath() == null) {
            throw new IocExtractorException("Legacy ledger record requires archivedPath at status "
                    + record.status() + ": " + record.key().value());
        }
        return record.archivedPath();
    }

    private IngestionRecord read(Path file) {
        try (Reader reader = Files.newBufferedReader(file)) {
            Properties props = new Properties();
            props.load(reader);
            SourceKey key = new SourceKey(required(props, "key", file));
            return new IngestionRecord(
                    key,
                    status(required(props, "status", file)),
                    Path.of(required(props, "originalPath", file)),
                    Path.of(required(props, "processingPath", file)),
                    optionalPath(props.getProperty("archivedPath")),
                    optionalInstant(props.getProperty("detectedAt")),
                    optionalInstant(props.getProperty("updatedAt")),
                    blankToNull(props.getProperty("reason")));
        } catch (IOException e) {
            throw new IocExtractorException("Failed to read legacy ingestion ledger record: " + file, e);
        }
    }

    private String required(Properties props, String key, Path file) {
        String value = blankToNull(props.getProperty(key));
        if (value == null) {
            throw new IocExtractorException("Legacy ledger file is missing property '" + key + "': " + file);
        }
        return value;
    }

    private Instant detectedAt(IngestionRecord record) {
        return record.detectedAt() == null ? Instant.now(clock) : record.detectedAt();
    }

    private Path optionalPath(String value) {
        String normalized = blankToNull(value);
        return normalized == null ? null : Path.of(normalized);
    }

    private Instant optionalInstant(String value) {
        String normalized = blankToNull(value);
        return normalized == null ? null : Instant.parse(normalized);
    }

    private IngestionStatus status(String value) {
        return IngestionStatus.valueOf(value);
    }

    private String checksum(Path file) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(Files.readAllBytes(file)));
        } catch (IOException e) {
            throw new IocExtractorException("Failed to read legacy ledger checksum: " + file, e);
        } catch (NoSuchAlgorithmException e) {
            throw new IocExtractorException("SHA-256 digest is unavailable", e);
        }
    }

    private void emitReplaySkipped(String name, Path file) {
        diagnosticSink.emit(diagnosticFactory.create(StorageDiagnosticCodes.IMPORT_IDEMPOTENT_REPLAY)
                .with("importName", name)
                .with("sourcePath", file.toString())
                .build());
        LogEvents.debug(LOGGER)
                .action(EventAction.LEDGER_IMPORT)
                .outcome(EventOutcome.UNKNOWN)
                .field(LogField.EVENT_TYPE, "skipped")
                .field(LogField.FILE_PATH, file.toString())
                .message("legacy ingestion ledger import skipped completed source")
                .log();
    }

    private void logImported(String name, Path file) {
        LogEvents.info(LOGGER)
                .action(EventAction.LEDGER_IMPORT)
                .outcome(EventOutcome.SUCCESS)
                .field(LogField.FILE_PATH, file.toString())
                .field("ioc.legacy_import.name", name)
                .message("legacy ingestion ledger imported")
                .log();
    }

    private void logSummary(LegacyLedgerImportSummary summary) {
        LogEvents.info(LOGGER)
                .action(EventAction.LEDGER_IMPORT)
                .outcome(summary.failed() == 0 ? EventOutcome.SUCCESS : EventOutcome.FAILURE)
                .field("ioc.legacy_import.scanned", summary.scanned())
                .field("ioc.legacy_import.imported", summary.imported())
                .field("ioc.legacy_import.skipped", summary.skipped())
                .field("ioc.legacy_import.failed", summary.failed())
                .message("legacy ingestion ledger import finished")
                .log();
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    public record LegacyLedgerImportSummary(int scanned, int imported, int skipped, int failed) {
    }
}
