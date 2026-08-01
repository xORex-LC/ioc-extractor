package com.iocextractor.adapter.in.ingest;

import com.iocextractor.application.ingest.IngestionRecord;
import com.iocextractor.application.ingest.IngestionStatus;
import com.iocextractor.application.ingest.SourceKey;
import com.iocextractor.application.ingest.SourceUnit;
import com.iocextractor.application.port.out.ingest.IngestionLedger;
import com.iocextractor.application.ingest.IngestionLedgerTransition;
import com.iocextractor.common.IocExtractorException;
import com.iocextractor.platform.concurrent.SynchronousKeyedExecutionGuard;
import com.iocextractor.platform.concurrent.WorkKey;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Properties;

/**
 * File-backed ingestion ledger. Each source key is represented by one
 * properties file. Expected-state transitions are serialized per key within
 * this adapter instance and each resulting file replacement is atomic.
 * Cross-process ownership is outside the supported deployment contract.
 */
public final class FileIngestionLedger implements IngestionLedger {

    private final Path ledgerDir;
    private final Clock clock;
    private final SynchronousKeyedExecutionGuard transitions = new SynchronousKeyedExecutionGuard();

    public FileIngestionLedger(Path ledgerDir, Clock clock) {
        this.ledgerDir = ledgerDir;
        this.clock = clock;
    }

    @Override
    public Optional<IngestionRecord> find(SourceKey key) {
        Path path = pathFor(key);
        if (!Files.exists(path)) {
            return Optional.empty();
        }
        return Optional.of(read(path));
    }

    @Override
    public IngestionLedgerTransition markClaimed(SourceUnit unit) {
        return transitions.execute(workKey(unit.key()), () -> {
            Optional<IngestionRecord> current = find(unit.key());
            if (current.isPresent()) {
                return current.orElseThrow().status() == IngestionStatus.CLAIMED
                        ? IngestionLedgerTransition.ALREADY_APPLIED
                        : IngestionLedgerTransition.CONFLICT;
            }
            write(new IngestionRecord(unit.key(), IngestionStatus.CLAIMED,
                    unit.originalPath(), unit.processingPath(), null,
                    unit.detectedAt(), Instant.now(clock), null));
            return IngestionLedgerTransition.APPLIED;
        });
    }

    @Override
    public IngestionLedgerTransition markSourceArchived(SourceKey key, Path archivedPath) {
        return transitions.execute(workKey(key), () -> {
            Optional<IngestionRecord> current = find(key);
            if (current.isEmpty()) {
                return IngestionLedgerTransition.MISSING;
            }
            IngestionRecord record = current.orElseThrow();
            if (record.status() == IngestionStatus.SOURCE_ARCHIVED) {
                return IngestionLedgerTransition.ALREADY_APPLIED;
            }
            if (record.status() != IngestionStatus.CLAIMED) {
                return IngestionLedgerTransition.CONFLICT;
            }
            write(new IngestionRecord(key, IngestionStatus.SOURCE_ARCHIVED,
                    record.originalPath(), record.processingPath(), archivedPath,
                    record.detectedAt(), Instant.now(clock), record.reason()));
            return IngestionLedgerTransition.APPLIED;
        });
    }

    @Override
    public IngestionLedgerTransition markFailed(SourceKey key, String reason) {
        return transitions.execute(workKey(key), () -> {
            Optional<IngestionRecord> current = find(key);
            if (current.isPresent() && current.orElseThrow().status() == IngestionStatus.FAILED) {
                return IngestionLedgerTransition.ALREADY_APPLIED;
            }
            if (current.isPresent()
                    && current.orElseThrow().status() == IngestionStatus.SOURCE_ARCHIVED) {
                return IngestionLedgerTransition.CONFLICT;
            }
            Instant now = Instant.now(clock);
            IngestionRecord record = current.orElse(new IngestionRecord(key, IngestionStatus.FAILED,
                    Path.of("unknown"), Path.of("unknown"), null, now, now, reason));
            write(new IngestionRecord(key, IngestionStatus.FAILED,
                    record.originalPath(), record.processingPath(), record.archivedPath(),
                    record.detectedAt(), now, reason));
            return IngestionLedgerTransition.APPLIED;
        });
    }

    @Override
    public List<IngestionRecord> findIncomplete() {
        return findRecords(record -> record.status() != IngestionStatus.SOURCE_ARCHIVED
                && record.status() != IngestionStatus.FAILED);
    }

    private List<IngestionRecord> findRecords(java.util.function.Predicate<IngestionRecord> predicate) {
        if (!Files.exists(ledgerDir)) {
            return List.of();
        }
        try (var files = Files.list(ledgerDir)) {
            return files
                    .filter(path -> path.getFileName().toString().endsWith(".properties"))
                    .map(this::read)
                    .filter(predicate)
                    .toList();
        } catch (IOException e) {
            throw new IocExtractorException("Failed to read ingestion ledger: " + ledgerDir, e);
        }
    }

    private IngestionRecord read(Path path) {
        try (Reader reader = Files.newBufferedReader(path)) {
            Properties props = new Properties();
            props.load(reader);
            SourceKey key = new SourceKey(props.getProperty("key"));
            return new IngestionRecord(
                    key,
                    status(props.getProperty("status")),
                    Path.of(props.getProperty("originalPath")),
                    Path.of(props.getProperty("processingPath")),
                    optionalPath(props.getProperty("archivedPath")),
                    optionalInstant(props.getProperty("detectedAt")),
                    optionalInstant(props.getProperty("updatedAt")),
                    blankToNull(props.getProperty("reason")));
        } catch (IOException e) {
            throw new IocExtractorException("Failed to read ingestion ledger record: " + path, e);
        }
    }

    private void write(IngestionRecord record) {
        try {
            Files.createDirectories(ledgerDir);
            Properties props = new Properties();
            props.setProperty("key", record.key().value());
            props.setProperty("status", record.status().name());
            props.setProperty("originalPath", record.originalPath().toString());
            props.setProperty("processingPath", record.processingPath().toString());
            props.setProperty("archivedPath", record.archivedPath() == null ? "" : record.archivedPath().toString());
            props.setProperty("detectedAt", record.detectedAt() == null ? "" : record.detectedAt().toString());
            props.setProperty("updatedAt", record.updatedAt() == null ? "" : record.updatedAt().toString());
            props.setProperty("reason", record.reason() == null ? "" : record.reason());

            Path target = pathFor(record.key());
            Path temp = Files.createTempFile(ledgerDir, record.key().value(), ".tmp");
            try (Writer writer = Files.newBufferedWriter(temp)) {
                props.store(writer, "ioc ingestion ledger");
            }
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new IocExtractorException("Failed to write ingestion ledger record: " + record.key().value(), e);
        }
    }

    private Path pathFor(SourceKey key) {
        return ledgerDir.resolve(key.value() + ".properties");
    }

    private WorkKey workKey(SourceKey key) {
        return WorkKey.of(key.value());
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

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
