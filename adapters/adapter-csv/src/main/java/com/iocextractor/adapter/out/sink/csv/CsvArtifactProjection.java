package com.iocextractor.adapter.out.sink.csv;

import com.iocextractor.application.artifact.CanonicalArtifact;
import com.iocextractor.application.port.out.artifact.CanonicalArtifactRepository;
import com.iocextractor.application.port.out.artifact.ArtifactProjection;
import com.iocextractor.application.port.out.artifact.ArtifactProjectionCommand;
import com.iocextractor.application.port.out.artifact.ArtifactProjectionResult;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
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
    private final DiagnosticFactory diagnostics;

    /** Creates a canonical-to-CSV projection adapter with explicit output and diagnostic policies. */
    public CsvArtifactProjection(CanonicalArtifactRepository repository,
                                 Map<String, List<String>> headers,
                                 Map<String, Path> paths,
                                 CSVFormat format,
                                 Charset charset,
                                 DiagnosticFactory diagnostics) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.headers = copyHeaders(Objects.requireNonNull(headers, "headers"));
        this.paths = Map.copyOf(Objects.requireNonNull(paths, "paths"));
        this.format = Objects.requireNonNull(format, "format");
        this.charset = charset == null ? StandardCharsets.UTF_8 : charset;
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
        CanonicalArtifact artifact = repository.load(artifactName);
        Path path = path(artifactName);
        var encodingLoss = write(artifactName, path, header, artifact);
        List<Diagnostic> outcomeDiagnostics = encodingLoss.detected()
                ? List.of(charsetDiagnostic(request, path, encodingLoss))
                : List.of();
        return new ArtifactProjectionResult(artifact.rows().size(), outcomeDiagnostics);
    }

    private CsvValueEncodingInspector.CsvEncodingLoss write(
            String artifactName, Path path, List<String> header, CanonicalArtifact artifact) {
        try {
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path temp = tempPath(path);
            var inspector = new CsvValueEncodingInspector(charset);
            inspector.inspectHeader(header);
            var writer = CsvIo.newWriter(temp, charset);
            try (CSVPrinter printer = new CSVPrinter(writer, format)) {
                printer.printRecord(header);
                for (var row : artifact.rows()) {
                    var values = new ArrayList<String>(header.size());
                    header.forEach(column -> values.add(row.value(column)));
                    inspector.inspectRow(values, format.getNullString());
                    printer.printRecord(values);
                }
            }
            moveIntoPlace(temp, path);
            LogEvents.info(log)
                    .action(EventAction.ARTIFACT_PROJECT)
                    .outcome(EventOutcome.SUCCESS)
                    .field(LogField.IOC_ARTIFACT_NAME, artifactName)
                    .field(LogField.FILE_PATH, path)
                    .field(LogField.IOC_ROWS, artifact.rows().size())
                    .message("artifact projection written")
                    .log();
            return inspector.loss();
        } catch (IOException e) {
            throw new IocExtractorException("Failed to write artifact projection '" + artifactName + "' to "
                    + path, e);
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
        try {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
