package com.iocextractor.adapter.out.sink.csv;

import com.iocextractor.application.export.ExportFormat;
import com.iocextractor.application.port.out.artifact.ArtifactProjection;
import com.iocextractor.application.port.out.artifact.ArtifactProjectionCommand;
import com.iocextractor.application.port.out.artifact.ArtifactProjectionResult;
import com.iocextractor.application.port.out.artifact.CanonicalArtifactStreamReader;
import com.iocextractor.common.IocExtractorException;
import com.iocextractor.diagnostics.Diagnostic;
import com.iocextractor.diagnostics.DiagnosticContextKeys;
import com.iocextractor.diagnostics.DiagnosticFactory;
import com.iocextractor.diagnostics.codes.SinkDiagnosticCodes;
import com.iocextractor.observability.EventAction;
import com.iocextractor.observability.EventOutcome;
import com.iocextractor.observability.LogField;
import com.iocextractor.observability.logging.LogEvents;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.csv.QuoteMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
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

    private final CanonicalArtifactStreamReader reader;
    private final Map<String, List<String>> headers;
    private final Map<String, Path> paths;
    private final CSVFormat format;
    private final Charset charset;
    private final DiagnosticFactory diagnostics;

    /** Creates a canonical-to-CSV projection adapter with explicit output and diagnostic policies. */
    public CsvArtifactProjection(CanonicalArtifactStreamReader reader,
                                 Map<String, List<String>> headers,
                                 Map<String, Path> paths,
                                 ExportFormat exportFormat,
                                 DiagnosticFactory diagnostics) {
        this.reader = Objects.requireNonNull(reader, "reader");
        this.headers = copyHeaders(Objects.requireNonNull(headers, "headers"));
        this.paths = Map.copyOf(Objects.requireNonNull(paths, "paths"));
        this.format = csvFormat(Objects.requireNonNull(exportFormat, "exportFormat"));
        this.charset = charset(exportFormat);
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
    }

    /**
     * Rewrites the CSV projection for one artifact from current canonical data.
     *
     * @param request projection operation identity
     * @return successfully installed projection outcome
     */
    @Override
    public ArtifactProjectionResult project(ArtifactProjectionCommand request) {
        Objects.requireNonNull(request, "request");
        String artifactName = request.artifactName();
        List<String> header = requireHeader(artifactName);
        Path path = path(artifactName);
        ProjectionWriteResult writeResult = write(artifactName, path, header);
        var encodingLoss = writeResult.encodingLoss();
        List<Diagnostic> outcomeDiagnostics = encodingLoss.detected()
                ? List.of(charsetDiagnostic(request, path, encodingLoss))
                : List.of();
        return new ArtifactProjectionResult(writeResult.rows(), outcomeDiagnostics);
    }

    private ProjectionWriteResult write(String artifactName, Path path, List<String> header) {
        Path temp = null;
        try {
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            temp = tempPath(path);
            var inspector = new CsvValueEncodingInspector(charset);
            inspector.inspectHeader(header);
            var writer = CsvIo.newWriter(temp, charset);
            int rows;
            try (CSVPrinter printer = new CSVPrinter(writer, format)) {
                printer.printRecord(header);
                rows = reader.stream(artifactName, row -> {
                    var values = new ArrayList<String>(header.size());
                    header.forEach(column -> values.add(row.value(column)));
                    inspector.inspectRow(values, format.getNullString());
                    try {
                        printer.printRecord(values);
                    } catch (IOException failure) {
                        throw new UncheckedIOException(failure);
                    }
                });
            }
            moveIntoPlace(temp, path);
            LogEvents.info(log)
                    .action(EventAction.ARTIFACT_PROJECT)
                    .outcome(EventOutcome.SUCCESS)
                    .field(LogField.IOC_ARTIFACT_NAME, artifactName)
                    .field(LogField.FILE_PATH, path)
                    .field(LogField.IOC_ROWS, rows)
                    .message("artifact projection written")
                    .log();
            return new ProjectionWriteResult(rows, inspector.loss());
        } catch (IOException | UncheckedIOException e) {
            deleteIncomplete(temp, e);
            throw new IocExtractorException("Failed to write artifact projection '" + artifactName + "' to "
                    + path, e);
        } catch (RuntimeException e) {
            deleteIncomplete(temp, e);
            throw e;
        }
    }

    private Diagnostic charsetDiagnostic(
            ArtifactProjectionCommand request,
            Path path,
            CsvValueEncodingInspector.CsvEncodingLoss loss) {
        return diagnostics.create(SinkDiagnosticCodes.CHARSET_UNMAPPABLE)
                .with("runId", request.runId())
                .with(DiagnosticContextKeys.ARTIFACT, request.artifactName())
                .with("path", path.toString())
                .with("charset", charset.name())
                .with("affectedValues", loss.affectedValues())
                .with("affectedRows", loss.affectedRows())
                .with("affectedHeaderValues", loss.affectedHeaderValues())
                .build();
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
        Path fileName = target.getFileName();
        if (fileName == null) {
            throw new IocExtractorException("Artifact projection path must name a CSV file: " + target);
        }
        Path parent = target.getParent();
        return Files.createTempFile(parent == null ? Path.of(".") : parent, fileName.toString(), ".tmp");
    }

    private void moveIntoPlace(Path temp, Path target) throws IOException {
        Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private void deleteIncomplete(Path temp, Exception failure) {
        if (temp == null) {
            return;
        }
        try {
            Files.deleteIfExists(temp);
        } catch (IOException cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
        }
    }

    private CSVFormat csvFormat(ExportFormat exportFormat) {
        if (!"csv".equalsIgnoreCase(exportFormat.type())) {
            throw new IllegalArgumentException("Mutable CSV projection requires csv export format");
        }
        if (exportFormat.delimiter().length() != 1 || exportFormat.quote().length() != 1) {
            throw new IllegalArgumentException("CSV delimiter and quote must each be one character");
        }
        return CSVFormat.Builder.create()
                .setDelimiter(exportFormat.delimiter().charAt(0))
                .setQuote(exportFormat.quote().charAt(0))
                .setNullString(exportFormat.nullLiteral())
                .setQuoteMode(QuoteMode.ALL_NON_NULL)
                .setRecordSeparator("\r\n")
                .build();
    }

    private Charset charset(ExportFormat exportFormat) {
        try {
            return Charset.forName(exportFormat.charset());
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("Unsupported CSV projection charset: " + exportFormat.charset(), failure);
        }
    }

    private record ProjectionWriteResult(int rows, CsvValueEncodingInspector.CsvEncodingLoss encodingLoss) {
    }
}
