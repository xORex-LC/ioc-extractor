package com.iocextractor.adapter.out.sink.csv;

import com.iocextractor.application.aggregation.ArtifactRow;
import com.iocextractor.application.aggregation.CanonicalArtifact;
import com.iocextractor.application.aggregation.PartitionArtifact;
import com.iocextractor.application.ingest.IngestionRecord;
import com.iocextractor.application.port.out.aggregation.CanonicalArtifactRepository;
import com.iocextractor.application.port.out.aggregation.PartitionArtifactRepository;
import com.iocextractor.common.IocExtractorException;
import com.iocextractor.observability.EventAction;
import com.iocextractor.observability.EventOutcome;
import com.iocextractor.observability.LogField;
import com.iocextractor.observability.logging.LogEvents;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVPrinter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * CSV-backed partition and canonical artifact repositories. This class owns CSV
 * dialect, paths and atomic file writes; the application service sees only rows
 * and artifact names.
 */
public final class CsvArtifactRepositories implements PartitionArtifactRepository, CanonicalArtifactRepository {

    private static final Logger log = LoggerFactory.getLogger(CsvArtifactRepositories.class);

    private final Map<String, CsvArtifactDefinition> definitions;
    private final Map<String, Path> canonicalPaths;
    private final CSVFormat readFormat;
    private final CSVFormat writeFormat;
    private final Charset charset;

    public CsvArtifactRepositories(List<CsvArtifactDefinition> definitions,
                                   Map<String, Path> canonicalPaths,
                                   CSVFormat readFormat,
                                   CSVFormat writeFormat) {
        this(definitions, canonicalPaths, readFormat, writeFormat, StandardCharsets.UTF_8);
    }

    public CsvArtifactRepositories(List<CsvArtifactDefinition> definitions,
                                   Map<String, Path> canonicalPaths,
                                   CSVFormat readFormat,
                                   CSVFormat writeFormat,
                                   Charset charset) {
        this.definitions = definitionsByName(definitions);
        this.canonicalPaths = Map.copyOf(Objects.requireNonNull(canonicalPaths, "canonicalPaths"));
        this.readFormat = Objects.requireNonNull(readFormat, "readFormat");
        this.writeFormat = Objects.requireNonNull(writeFormat, "writeFormat");
        this.charset = charset == null ? StandardCharsets.UTF_8 : charset;
    }

    @Override
    public List<PartitionArtifact> readPartitions(List<IngestionRecord> records) {
        List<PartitionArtifact> partitions = new ArrayList<>();
        for (IngestionRecord record : records) {
            for (Path path : record.partitions()) {
                String artifactName = artifactNameFor(path);
                partitions.add(new PartitionArtifact(record.key(), artifactName, path, readRows(path)));
            }
        }
        return partitions;
    }

    @Override
    public CanonicalArtifact load(String artifactName) {
        CsvArtifactDefinition definition = requireDefinition(artifactName);
        Path path = canonicalPath(artifactName);
        if (!Files.exists(path)) {
            return new CanonicalArtifact(artifactName, definition.mapper().header(), List.of());
        }
        return new CanonicalArtifact(artifactName, definition.mapper().header(), readRows(path));
    }

    @Override
    public void write(String artifactName, CanonicalArtifact artifact) {
        Path path = canonicalPath(artifactName);
        try {
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            Path temp = tempPath(path);
            CountingCharsetWriter writer = CsvIo.newCountingWriter(temp, charset);
            try (CSVPrinter printer = new CSVPrinter(writer, writeFormat)) {
                printer.printRecord(artifact.header());
                for (ArtifactRow row : artifact.rows()) {
                    List<String> values = artifact.header().stream()
                            .map(row::value)
                            .toList();
                    printer.printRecord(values);
                }
            }
            if (writer.unmappable() > 0) {
                LogEvents.warn(log)
                        .action(EventAction.ARTIFACT_WRITE)
                        .outcome(EventOutcome.SUCCESS)
                        .field(LogField.IOC_ARTIFACT_NAME, artifactName)
                        .field(LogField.FILE_PATH, path)
                        .message("canonical artifact has values not representable in charset " + charset
                                + "; replaced in " + writer.unmappable() + " field(s)")
                        .log();
            }
            moveIntoPlace(temp, path);
        } catch (IOException e) {
            throw new IocExtractorException("Failed to write canonical artifact '" + artifactName + "' to " + path, e);
        }
    }

    private List<ArtifactRow> readRows(Path path) {
        if (!Files.exists(path)) {
            return List.of();
        }
        try (Reader reader = CsvIo.newReader(path, charset);
             CSVParser parser = CSVParser.parse(reader, readFormat)) {
            List<String> headers = parser.getHeaderNames();
            List<ArtifactRow> rows = new ArrayList<>();
            parser.forEach(record -> {
                Map<String, String> values = new LinkedHashMap<>();
                for (String header : headers) {
                    values.put(header, record.get(header));
                }
                rows.add(ArtifactRow.ordered(values));
            });
            return rows;
        } catch (IOException e) {
            throw new IocExtractorException("Failed to read artifact CSV: " + path, e);
        }
    }

    private String artifactNameFor(Path path) {
        for (Path part : path) {
            String value = part.toString();
            if (definitions.containsKey(value)) {
                return value;
            }
        }
        throw new IocExtractorException("Cannot resolve artifact name from partition path: " + path);
    }

    private Path canonicalPath(String artifactName) {
        Path path = canonicalPaths.get(artifactName);
        if (path == null) {
            throw new IocExtractorException("Missing canonical artifact path: " + artifactName);
        }
        return path;
    }

    private CsvArtifactDefinition requireDefinition(String artifactName) {
        CsvArtifactDefinition definition = definitions.get(artifactName);
        if (definition == null) {
            throw new IocExtractorException("Unknown CSV artifact definition: " + artifactName);
        }
        return definition;
    }

    private Map<String, CsvArtifactDefinition> definitionsByName(List<CsvArtifactDefinition> definitions) {
        Map<String, CsvArtifactDefinition> byName = new LinkedHashMap<>();
        for (CsvArtifactDefinition definition : definitions) {
            byName.put(definition.name(), definition);
        }
        return Map.copyOf(byName);
    }

    private Path tempPath(Path target) throws IOException {
        Path parent = target.getParent() == null ? Path.of(".") : target.getParent();
        return Files.createTempFile(parent, target.getFileName().toString(), ".tmp");
    }

    private void moveIntoPlace(Path temp, Path target) throws IOException {
        try {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
