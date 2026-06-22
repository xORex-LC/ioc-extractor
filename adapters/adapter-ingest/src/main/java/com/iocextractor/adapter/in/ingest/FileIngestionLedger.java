package com.iocextractor.adapter.in.ingest;

import com.iocextractor.application.ingest.IngestionRecord;
import com.iocextractor.application.ingest.IngestionStatus;
import com.iocextractor.application.ingest.SourceKey;
import com.iocextractor.application.ingest.SourceUnit;
import com.iocextractor.application.port.out.ingest.IngestionLedger;
import com.iocextractor.common.IocExtractorException;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Properties;

/**
 * File-backed ingestion ledger. Each source key is represented by one
 * properties file, which keeps updates simple and atomic enough for the
 * single-worker invariant of stage 10.
 */
public final class FileIngestionLedger implements IngestionLedger {

    private static final String PARTITION_SEPARATOR = "\n";

    private final Path ledgerDir;
    private final Clock clock;

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
    public void markClaimed(SourceUnit unit) {
        write(new IngestionRecord(unit.key(), IngestionStatus.CLAIMED,
                unit.originalPath(), unit.processingPath(), null, List.of(),
                unit.detectedAt(), Instant.now(clock), null));
    }

    @Override
    public void markPartitionWritten(SourceKey key, List<Path> partitions) {
        IngestionRecord record = require(key);
        write(new IngestionRecord(key, IngestionStatus.PARTITION_WRITTEN,
                record.originalPath(), record.processingPath(), record.archivedPath(),
                partitions, record.detectedAt(), Instant.now(clock), record.reason()));
    }

    @Override
    public void markLedgerRecorded(SourceKey key) {
        IngestionRecord record = require(key);
        write(new IngestionRecord(key, IngestionStatus.LEDGER_RECORDED,
                record.originalPath(), record.processingPath(), record.archivedPath(),
                record.partitions(), record.detectedAt(), Instant.now(clock), record.reason()));
    }

    @Override
    public void markSourceArchived(SourceKey key, Path archivedPath) {
        IngestionRecord record = require(key);
        write(new IngestionRecord(key, IngestionStatus.SOURCE_ARCHIVED,
                record.originalPath(), record.processingPath(), archivedPath,
                record.partitions(), record.detectedAt(), Instant.now(clock), record.reason()));
    }

    @Override
    public void markAggregated(SourceKey key) {
        IngestionRecord record = require(key);
        write(new IngestionRecord(key, IngestionStatus.AGGREGATED,
                record.originalPath(), record.processingPath(), record.archivedPath(),
                record.partitions(), record.detectedAt(), Instant.now(clock), record.reason()));
    }

    @Override
    public void markFailed(SourceKey key, String reason) {
        IngestionRecord record = find(key).orElse(new IngestionRecord(key, IngestionStatus.FAILED,
                Path.of("unknown"), Path.of("unknown"), null, List.of(),
                Instant.now(clock), Instant.now(clock), reason));
        write(new IngestionRecord(key, IngestionStatus.FAILED,
                record.originalPath(), record.processingPath(), record.archivedPath(),
                record.partitions(), record.detectedAt(), Instant.now(clock), reason));
    }

    @Override
    public List<IngestionRecord> findIncomplete() {
        return findRecords(record -> record.status() != IngestionStatus.SOURCE_ARCHIVED
                && record.status() != IngestionStatus.AGGREGATED
                && record.status() != IngestionStatus.FAILED);
    }

    @Override
    public List<IngestionRecord> findReadyForAggregation() {
        return findRecords(record -> record.status() == IngestionStatus.SOURCE_ARCHIVED
                && !record.partitions().isEmpty());
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

    private IngestionRecord require(SourceKey key) {
        return find(key).orElseThrow(() -> new IocExtractorException("Missing ingestion ledger record: " + key.value()));
    }

    private IngestionRecord read(Path path) {
        try (Reader reader = Files.newBufferedReader(path)) {
            Properties props = new Properties();
            props.load(reader);
            SourceKey key = new SourceKey(props.getProperty("key"));
            return new IngestionRecord(
                    key,
                    IngestionStatus.valueOf(props.getProperty("status")),
                    Path.of(props.getProperty("originalPath")),
                    Path.of(props.getProperty("processingPath")),
                    optionalPath(props.getProperty("archivedPath")),
                    partitions(props.getProperty("partitions", "")),
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
            props.setProperty("partitions", join(record.partitions()));
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

    private Path optionalPath(String value) {
        String normalized = blankToNull(value);
        return normalized == null ? null : Path.of(normalized);
    }

    private Instant optionalInstant(String value) {
        String normalized = blankToNull(value);
        return normalized == null ? null : Instant.parse(normalized);
    }

    private List<Path> partitions(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return Arrays.stream(value.split(PARTITION_SEPARATOR))
                .filter(part -> !part.isBlank())
                .map(Path::of)
                .toList();
    }

    private String join(List<Path> partitions) {
        return String.join(PARTITION_SEPARATOR, partitions.stream().map(Path::toString).toList());
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
