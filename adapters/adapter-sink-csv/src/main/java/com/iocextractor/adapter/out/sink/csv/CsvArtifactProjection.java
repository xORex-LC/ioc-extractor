package com.iocextractor.adapter.out.sink.csv;

import com.iocextractor.application.aggregation.CanonicalArtifact;
import com.iocextractor.application.port.out.aggregation.CanonicalArtifactRepository;
import com.iocextractor.application.port.out.aggregation.ArtifactProjection;
import com.iocextractor.common.IocExtractorException;
import com.iocextractor.observability.EventAction;
import com.iocextractor.observability.EventOutcome;
import com.iocextractor.observability.LogField;
import com.iocextractor.observability.logging.LogEvents;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Writes CSV projections from the canonical artifact repository. CSV is a
 * derived artifact here; the repository behind the port remains the source of
 * truth.
 */
public final class CsvArtifactProjection implements ArtifactProjection {

    private static final Logger log = LoggerFactory.getLogger(CsvArtifactProjection.class);

    private final CanonicalArtifactRepository repository;
    private final Map<String, List<String>> headers;
    private final Map<String, Path> paths;
    private final CSVFormat format;
    private final Charset charset;

    public CsvArtifactProjection(CanonicalArtifactRepository repository,
                                 Map<String, List<String>> headers,
                                 Map<String, Path> paths,
                                 CSVFormat format,
                                 Charset charset) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.headers = copyHeaders(Objects.requireNonNull(headers, "headers"));
        this.paths = Map.copyOf(Objects.requireNonNull(paths, "paths"));
        this.format = Objects.requireNonNull(format, "format");
        this.charset = charset == null ? StandardCharsets.UTF_8 : charset;
    }

    /**
     * Rewrites the CSV projection for one artifact from current canonical data.
     *
     * @param artifactName artifact to project
     */
    @Override
    public void project(String artifactName) {
        List<String> header = requireHeader(artifactName);
        CanonicalArtifact artifact = repository.load(artifactName);
        write(artifactName, header, artifact);
    }

    private void write(String artifactName, List<String> header, CanonicalArtifact artifact) {
        Path path = path(artifactName);
        try {
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            Path temp = tempPath(path);
            CountingCharsetWriter writer = CsvIo.newCountingWriter(temp, charset);
            try (CSVPrinter printer = new CSVPrinter(writer, format)) {
                printer.printRecord(header);
                for (var row : artifact.rows()) {
                    printer.printRecord(header.stream().map(row::value).toList());
                }
            }
            if (writer.unmappable() > 0) {
                LogEvents.warn(log)
                        .action(EventAction.ARTIFACT_WRITE)
                        .outcome(EventOutcome.SUCCESS)
                        .field(LogField.IOC_ARTIFACT_NAME, artifactName)
                        .field(LogField.FILE_PATH, path)
                        .message("artifact projection has values not representable in charset " + charset
                                + "; replaced in " + writer.unmappable() + " field(s)")
                        .log();
            }
            moveIntoPlace(temp, path);
            LogEvents.info(log)
                    .action(EventAction.ARTIFACT_WRITE)
                    .outcome(EventOutcome.SUCCESS)
                    .field(LogField.IOC_ARTIFACT_NAME, artifactName)
                    .field(LogField.FILE_PATH, path)
                    .field(LogField.IOC_ROWS, artifact.rows().size())
                    .message("artifact projection written")
                    .log();
        } catch (IOException e) {
            throw new IocExtractorException("Failed to write artifact projection '" + artifactName + "' to "
                    + path, e);
        }
    }

    private List<String> requireHeader(String artifactName) {
        List<String> header = headers.get(artifactName);
        if (header == null) {
            throw new IocExtractorException("Unknown CSV artifact projection: " + artifactName);
        }
        return header;
    }

    private Path path(String artifactName) {
        Path path = paths.get(artifactName);
        if (path == null) {
            throw new IocExtractorException("Missing artifact projection path: " + artifactName);
        }
        return path;
    }

    private Map<String, List<String>> copyHeaders(Map<String, List<String>> source) {
        Map<String, List<String>> byName = new LinkedHashMap<>();
        source.forEach((name, header) -> byName.put(name, List.copyOf(header)));
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
