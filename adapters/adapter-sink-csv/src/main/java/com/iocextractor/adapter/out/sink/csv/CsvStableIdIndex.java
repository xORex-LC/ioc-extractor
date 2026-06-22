package com.iocextractor.adapter.out.sink.csv;

import com.iocextractor.application.aggregation.ArtifactRowKey;
import com.iocextractor.application.aggregation.StableArtifactId;
import com.iocextractor.application.port.out.aggregation.StableIdIndex;
import com.iocextractor.common.IocExtractorException;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVPrinter;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Sidecar CSV stable id index. It is intentionally adapter-local; moving to
 * SQLite/JDBC later should replace this implementation behind the same port.
 */
public final class CsvStableIdIndex implements StableIdIndex {

    private static final CSVFormat FORMAT = CSVFormat.Builder.create()
            .setDelimiter(';')
            .setQuote('"')
            .setHeader()
            .setSkipHeaderRecord(true)
            .build();
    private static final CSVFormat WRITE_FORMAT = CSVFormat.Builder.create()
            .setDelimiter(';')
            .setQuote('"')
            .setRecordSeparator("\r\n")
            .build();

    private final Path path;
    private final Clock clock;
    private final Map<IndexKey, IndexEntry> entries = new LinkedHashMap<>();
    private long nextId = 1L;

    public CsvStableIdIndex(Path path, Clock clock) {
        this.path = Objects.requireNonNull(path, "path");
        this.clock = Objects.requireNonNull(clock, "clock");
        load();
    }

    @Override
    public StableArtifactId getOrCreate(String artifactName, ArtifactRowKey key) {
        IndexKey indexKey = new IndexKey(artifactName, key.value());
        IndexEntry existing = entries.get(indexKey);
        if (existing != null) {
            return new StableArtifactId(existing.id(), false);
        }
        Instant now = Instant.now(clock);
        IndexEntry created = new IndexEntry(artifactName, key.value(), nextId++, now, now);
        entries.put(indexKey, created);
        return new StableArtifactId(created.id(), true);
    }

    @Override
    public void save() {
        try {
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            Path temp = Files.createTempFile(path.getParent() == null ? Path.of(".") : path.getParent(),
                    path.getFileName().toString(), ".tmp");
            try (BufferedWriter writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8);
                 CSVPrinter printer = new CSVPrinter(writer, WRITE_FORMAT)) {
                printer.printRecord("artifact", "key", "id", "created_at", "updated_at");
                for (IndexEntry entry : entries.values()) {
                    printer.printRecord(entry.artifact(), entry.key(), entry.id(),
                            entry.createdAt(), entry.updatedAt());
                }
            }
            try {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new IocExtractorException("Failed to write stable id index: " + path, e);
        }
    }

    private void load() {
        if (!Files.exists(path)) {
            return;
        }
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8);
             CSVParser parser = CSVParser.parse(reader, FORMAT)) {
            for (var record : parser) {
                IndexEntry entry = new IndexEntry(
                        record.get("artifact"),
                        record.get("key"),
                        Long.parseLong(record.get("id")),
                        Instant.parse(record.get("created_at")),
                        Instant.parse(record.get("updated_at")));
                entries.put(new IndexKey(entry.artifact(), entry.key()), entry);
                nextId = Math.max(nextId, entry.id() + 1L);
            }
        } catch (IOException e) {
            throw new IocExtractorException("Failed to read stable id index: " + path, e);
        }
    }

    private record IndexKey(String artifact, String key) {
    }

    private record IndexEntry(String artifact, String key, long id, Instant createdAt, Instant updatedAt) {
    }
}
