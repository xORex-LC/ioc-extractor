package com.iocextractor.adapter.in.ingest;

import com.iocextractor.application.ingest.IngestionRecord;
import com.iocextractor.application.artifact.lifecycle.ObservationId;
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
 * File-backed ingestion ledger. Each durable delivery observation is represented by one
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
    public Optional<IngestionRecord> find(ObservationId observationId) {
        Path path = pathFor(observationId);
        if (!Files.exists(path) && observationId.value().startsWith("legacy:")) {
            path = ledgerDir.resolve(observationId.value().substring("legacy:".length()) + ".properties");
        }
        if (!Files.exists(path)) {
            return Optional.empty();
        }
        return Optional.of(read(path));
    }

    @Override
    public IngestionLedgerTransition markClaimed(SourceUnit unit) {
        return transitions.execute(workKey(unit.observationId()), () -> {
            Optional<IngestionRecord> current = find(unit.observationId());
            if (current.isPresent()) {
                return current.orElseThrow().status() == IngestionStatus.CLAIMED
                        ? IngestionLedgerTransition.ALREADY_APPLIED
                        : IngestionLedgerTransition.CONFLICT;
            }
            write(new IngestionRecord(unit.observationId(), unit.key(), IngestionStatus.CLAIMED,
                    unit.originalPath(), unit.processingPath(), null,
                    unit.detectedAt(), Instant.now(clock), null));
            return IngestionLedgerTransition.APPLIED;
        });
    }

    @Override
    public IngestionLedgerTransition markSourceArchived(ObservationId observationId, Path archivedPath) {
        return transitions.execute(workKey(observationId), () -> {
            Optional<IngestionRecord> current = find(observationId);
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
            write(new IngestionRecord(observationId, record.key(), IngestionStatus.SOURCE_ARCHIVED,
                    record.originalPath(), record.processingPath(), archivedPath,
                    record.detectedAt(), Instant.now(clock), record.reason()));
            return IngestionLedgerTransition.APPLIED;
        });
    }

    @Override
    public IngestionLedgerTransition markFailed(ObservationId observationId,
                                                SourceKey key,
                                                String reason) {
        return transitions.execute(workKey(observationId), () -> {
            Optional<IngestionRecord> current = find(observationId);
            if (current.isPresent() && current.orElseThrow().status() == IngestionStatus.FAILED) {
                return IngestionLedgerTransition.ALREADY_APPLIED;
            }
            if (current.isPresent()
                    && current.orElseThrow().status() == IngestionStatus.SOURCE_ARCHIVED) {
                return IngestionLedgerTransition.CONFLICT;
            }
            Instant now = Instant.now(clock);
            IngestionRecord record = current.orElse(new IngestionRecord(
                    observationId, key, IngestionStatus.FAILED,
                    Path.of("unknown"), Path.of("unknown"), null, now, now, reason));
            write(new IngestionRecord(observationId, record.key(), IngestionStatus.FAILED,
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
                    observationId(props, key),
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
            props.setProperty("observationId", record.observationId().value());
            props.setProperty("key", record.key().value());
            props.setProperty("status", record.status().name());
            props.setProperty("originalPath", record.originalPath().toString());
            props.setProperty("processingPath", record.processingPath().toString());
            props.setProperty("archivedPath", record.archivedPath() == null ? "" : record.archivedPath().toString());
            props.setProperty("detectedAt", record.detectedAt() == null ? "" : record.detectedAt().toString());
            props.setProperty("updatedAt", record.updatedAt() == null ? "" : record.updatedAt().toString());
            props.setProperty("reason", record.reason() == null ? "" : record.reason());

            Path target = pathFor(record.observationId());
            Path temp = Files.createTempFile(ledgerDir, fileToken(record.observationId()), ".tmp");
            try (Writer writer = Files.newBufferedWriter(temp)) {
                props.store(writer, "ioc ingestion ledger");
            }
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new IocExtractorException(
                    "Failed to write ingestion ledger record: " + record.observationId().value(), e);
        }
    }

    private Path pathFor(ObservationId observationId) {
        return ledgerDir.resolve(fileToken(observationId) + ".properties");
    }

    private WorkKey workKey(ObservationId observationId) {
        return WorkKey.of(observationId.value());
    }

    private String fileToken(ObservationId observationId) {
        return java.util.HexFormat.of().formatHex(digest(observationId.value()));
    }

    private byte[] digest(String value) {
        try {
            return java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private ObservationId observationId(Properties properties, SourceKey key) {
        String value = blankToNull(properties.getProperty("observationId"));
        return value == null ? ObservationId.legacy(key.value()) : new ObservationId(value);
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
