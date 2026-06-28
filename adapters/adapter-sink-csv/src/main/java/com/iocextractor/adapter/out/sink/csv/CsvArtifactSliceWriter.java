package com.iocextractor.adapter.out.sink.csv;

import com.iocextractor.application.artifact.ArtifactRow;
import com.iocextractor.application.export.AvailableSlice;
import com.iocextractor.application.export.ExportRun;
import com.iocextractor.application.export.ExportRunStatus;
import com.iocextractor.application.export.SliceInspection;
import com.iocextractor.application.export.SliceInspectionState;
import com.iocextractor.application.export.SnapshotArtifactMetadata;
import com.iocextractor.application.export.SnapshotMetadata;
import com.iocextractor.application.export.SnapshotRequest;
import com.iocextractor.application.export.StagedSlice;
import com.iocextractor.application.port.out.export.ArtifactSliceWriter;
import com.iocextractor.application.port.out.export.SliceManifestCodec;
import com.iocextractor.application.port.out.export.SnapshotRowConsumer;
import com.iocextractor.application.port.out.export.SnapshotSliceReader;
import com.iocextractor.diagnostics.Diagnostic;
import com.iocextractor.diagnostics.DiagnosticException;
import com.iocextractor.diagnostics.DiagnosticFactory;
import com.iocextractor.diagnostics.codes.ExportDiagnosticCodes;
import com.iocextractor.diagnostics.sink.DiagnosticSink;
import com.iocextractor.diagnostics.sink.NoopDiagnosticSink;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Streams deterministic CSV slices into durable staging and publishes them by one atomic rename.
 *
 * <p>The adapter owns only bytes and filesystem state. Export ledger transitions remain in the
 * application saga; this class neither reads nor writes the service database.
 */
public final class CsvArtifactSliceWriter implements ArtifactSliceWriter, SnapshotRowConsumer {

    private final SliceDirectoryLayout layout;
    private final SliceManifestCodec codec;
    private final SliceTreeVerifier verifier;
    private final SliceFileOperations fileOperations;
    private final DiagnosticSink diagnosticSink;
    private final DiagnosticFactory diagnosticFactory;

    private CsvSliceMaterialization active;

    /**
     * Creates a production writer using strict NIO durability and no-op diagnostic delivery.
     *
     * @param root local root containing staging and published profile directories
     * @param codec manifest serialization boundary
     */
    public CsvArtifactSliceWriter(Path root, SliceManifestCodec codec) {
        this(root, codec, new NioSliceFileOperations(), NoopDiagnosticSink.INSTANCE,
                new DiagnosticFactory(Clock.systemUTC()));
    }

    /**
     * Creates a production writer with explicit diagnostic delivery.
     *
     * @param root local root containing staging and published profile directories
     * @param codec manifest serialization boundary
     * @param diagnosticSink destination for filesystem protocol diagnostics
     * @param diagnosticFactory factory controlling diagnostic timestamps
     */
    public CsvArtifactSliceWriter(Path root,
                                  SliceManifestCodec codec,
                                  DiagnosticSink diagnosticSink,
                                  DiagnosticFactory diagnosticFactory) {
        this(root, codec, new NioSliceFileOperations(), diagnosticSink, diagnosticFactory);
    }

    CsvArtifactSliceWriter(Path root,
                           SliceManifestCodec codec,
                           SliceFileOperations fileOperations,
                           DiagnosticSink diagnosticSink,
                           DiagnosticFactory diagnosticFactory) {
        this.layout = new SliceDirectoryLayout(root);
        this.codec = Objects.requireNonNull(codec, "codec");
        this.verifier = new SliceTreeVerifier(codec);
        this.fileOperations = Objects.requireNonNull(fileOperations, "fileOperations");
        this.diagnosticSink = Objects.requireNonNull(diagnosticSink, "diagnosticSink");
        this.diagnosticFactory = Objects.requireNonNull(diagnosticFactory, "diagnosticFactory");
    }

    @Override
    public synchronized StagedSlice stage(ExportRun run,
                                          SnapshotRequest request,
                                          SnapshotSliceReader reader) {
        Objects.requireNonNull(run, "run");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(reader, "reader");
        validateStageRequest(run, request);
        Path staging = layout.staging(run);
        try {
            if (Files.exists(staging, LinkOption.NOFOLLOW_LINKS)
                    || Files.exists(layout.available(run), LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalStateException("slice path already exists; inspect/recover before staging");
            }
            createDirectoriesDurably(layout.stagingParent());
            Files.createDirectory(staging);
            fileOperations.forceDirectory(layout.stagingParent());
            active = new CsvSliceMaterialization(run, request.plan(), staging, codec, fileOperations);
            try (CsvSliceMaterialization ignored = active) {
                reader.stream(request, this);
                if (!active.ended()) {
                    throw new IllegalStateException("snapshot reader returned before end callback");
                }
            } finally {
                active = null;
            }
            VerifiedSlice verified = verifier.verify(staging, run);
            if (!verified.successPresent()) {
                throw new InvalidSliceException("newly staged slice has no _SUCCESS marker");
            }
            return staged(run, verified);
        } catch (SliceWriteException | IOException e) {
            throw failure(ExportDiagnosticCodes.SLICE_WRITE_FAILED, run, staging, e);
        } catch (InvalidSliceException e) {
            throw invalid(run, staging, reason(e));
        }
    }

    @Override
    public synchronized SliceInspection inspect(ExportRun run) {
        Objects.requireNonNull(run, "run");
        Path staging = layout.staging(run);
        Path available = layout.available(run);
        boolean hasStaging = Files.exists(staging, LinkOption.NOFOLLOW_LINKS);
        boolean hasAvailable = Files.exists(available, LinkOption.NOFOLLOW_LINKS);
        if (hasStaging && hasAvailable) {
            return new SliceInspection(run.runId(), SliceInspectionState.CONFLICT, null, null,
                    "both staging and available slice paths exist");
        }
        if (!hasStaging && !hasAvailable) {
            return new SliceInspection(run.runId(), SliceInspectionState.MISSING, null, null, null);
        }
        Path path = hasAvailable ? available : staging;
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) {
            InvalidSliceException failure = new InvalidSliceException("slice path is not a physical directory");
            emit(ExportDiagnosticCodes.MANIFEST_INVALID, run, path, failure);
            return new SliceInspection(run.runId(), SliceInspectionState.CORRUPT, null, null,
                    failure.getMessage());
        }
        if (hasAvailable
                && !Files.exists(path.resolve(SliceTreeVerifier.MANIFEST_FILE), LinkOption.NOFOLLOW_LINKS)) {
            InvalidSliceException failure = new InvalidSliceException("available slice has no manifest.json");
            emit(ExportDiagnosticCodes.MANIFEST_INVALID, run, path, failure);
            return new SliceInspection(run.runId(), SliceInspectionState.CORRUPT, null, null,
                    failure.getMessage());
        }
        if (!Files.exists(path.resolve(SliceTreeVerifier.MANIFEST_FILE), LinkOption.NOFOLLOW_LINKS)) {
            return new SliceInspection(run.runId(), SliceInspectionState.PARTIAL, null, null, null);
        }
        try {
            VerifiedSlice verified = verifier.verify(path, run);
            SliceInspectionState state = hasAvailable
                    ? requireAvailableSuccess(verified)
                    : verified.successPresent() ? SliceInspectionState.STAGED : SliceInspectionState.RECOVERABLE;
            return new SliceInspection(run.runId(), state, verified.manifestSha256(),
                    verified.manifest(), null);
        } catch (InvalidSliceException e) {
            emit(ExportDiagnosticCodes.MANIFEST_INVALID, run, path, e);
            return new SliceInspection(run.runId(), SliceInspectionState.CORRUPT, null, null, reason(e));
        }
    }

    @Override
    public synchronized StagedSlice recoverStaging(ExportRun run) {
        SliceInspection inspection = inspect(run);
        if (inspection.state() == SliceInspectionState.STAGED) {
            return new StagedSlice(run.runId(), run.sliceName(), inspection.manifestSha256(),
                    inspection.manifest());
        }
        if (inspection.state() != SliceInspectionState.RECOVERABLE) {
            throw invalid(run, layout.staging(run),
                    "staging is not recoverable: " + inspection.state());
        }
        Path staging = layout.staging(run);
        try {
            Path success = staging.resolve(SliceTreeVerifier.SUCCESS_FILE);
            Files.writeString(success, inspection.manifestSha256() + "\n", StandardCharsets.US_ASCII,
                    java.nio.file.StandardOpenOption.CREATE_NEW, java.nio.file.StandardOpenOption.WRITE);
            fileOperations.forceFile(success);
            fileOperations.forceDirectory(staging);
            fileOperations.forceDirectory(layout.stagingParent());
            VerifiedSlice verified = verifier.verify(staging, run);
            return staged(run, verified);
        } catch (IOException e) {
            throw failure(ExportDiagnosticCodes.SLICE_WRITE_FAILED, run, staging, e);
        } catch (InvalidSliceException e) {
            throw invalid(run, staging, reason(e));
        }
    }

    @Override
    public synchronized AvailableSlice makeAvailable(ExportRun run) {
        SliceInspection inspection = inspect(run);
        if (inspection.state() == SliceInspectionState.AVAILABLE) {
            return new AvailableSlice(run.runId(), run.sliceName(), inspection.manifestSha256(),
                    inspection.manifest());
        }
        if (inspection.state() != SliceInspectionState.STAGED) {
            throw invalid(run, layout.staging(run), "slice is not staged: " + inspection.state());
        }
        Path staging = layout.staging(run);
        Path target = layout.available(run);
        try {
            createDirectoriesDurably(layout.profile(run));
            fileOperations.moveAtomically(staging, target);
            fileOperations.forceDirectory(layout.profile(run));
            fileOperations.forceDirectory(layout.stagingParent());
            VerifiedSlice verified = verifier.verify(target, run);
            if (!verified.successPresent()) {
                throw new InvalidSliceException("available slice has no _SUCCESS marker");
            }
            return available(run, verified);
        } catch (AtomicMoveNotSupportedException e) {
            throw failure(ExportDiagnosticCodes.ATOMIC_PUBLISH_UNSUPPORTED, run, target, e);
        } catch (IOException e) {
            throw failure(ExportDiagnosticCodes.SLICE_WRITE_FAILED, run, target, e);
        } catch (InvalidSliceException e) {
            throw invalid(run, target, reason(e));
        }
    }

    @Override
    public synchronized void discardStaging(ExportRun run) {
        Objects.requireNonNull(run, "run");
        Path staging = layout.staging(run);
        if (!Files.exists(staging, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (var paths = Files.walk(staging)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
            fileOperations.forceDirectory(layout.stagingParent());
        } catch (IOException e) {
            throw failure(ExportDiagnosticCodes.SLICE_WRITE_FAILED, run, staging, e);
        }
    }

    @Override
    public void begin(SnapshotMetadata metadata) {
        session().begin(metadata);
    }

    @Override
    public void beginArtifact(SnapshotArtifactMetadata artifact) {
        session().beginArtifact(artifact);
    }

    @Override
    public void row(ArtifactRow row) {
        session().row(row);
    }

    @Override
    public void endArtifact() {
        session().endArtifact();
    }

    @Override
    public void end() {
        session().end();
    }

    private CsvSliceMaterialization session() {
        if (active == null) {
            throw new IllegalStateException("snapshot callback outside an active stage operation");
        }
        return active;
    }

    private void validateStageRequest(ExportRun run, SnapshotRequest request) {
        if (run.status() != ExportRunStatus.STARTED) {
            throw new IllegalArgumentException("only a STARTED export run can be staged");
        }
        if (!request.plan().profile().name().equals(run.profile())
                || !request.plan().planHash().equals(run.planHash())) {
            throw new IllegalArgumentException("export request plan does not match run identity");
        }
        var fileNames = request.plan().artifacts().stream().map(artifact -> artifact.fileName()).toList();
        if (fileNames.stream().distinct().count() != fileNames.size()
                || fileNames.contains(SliceTreeVerifier.MANIFEST_FILE)
                || fileNames.contains(SliceTreeVerifier.SUCCESS_FILE)) {
            throw new IllegalArgumentException("export artifact file names must be unique and non-reserved");
        }
    }

    private SliceInspectionState requireAvailableSuccess(VerifiedSlice verified) {
        if (!verified.successPresent()) {
            throw new InvalidSliceException("available slice has no _SUCCESS marker");
        }
        return SliceInspectionState.AVAILABLE;
    }

    /** Creates missing path segments and persists every newly linked directory entry. */
    private void createDirectoriesDurably(Path directory) throws IOException {
        List<Path> missing = new ArrayList<>();
        Path current = directory.toAbsolutePath().normalize();
        while (current != null && !Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
            missing.add(current);
            current = current.getParent();
        }
        Files.createDirectories(directory);
        for (int index = missing.size() - 1; index >= 0; index--) {
            Path created = missing.get(index);
            Path parent = created.getParent();
            if (parent != null) {
                fileOperations.forceDirectory(parent);
            }
            fileOperations.forceDirectory(created);
        }
    }

    private StagedSlice staged(ExportRun run, VerifiedSlice verified) {
        return new StagedSlice(run.runId(), run.sliceName(), verified.manifestSha256(), verified.manifest());
    }

    private AvailableSlice available(ExportRun run, VerifiedSlice verified) {
        return new AvailableSlice(run.runId(), run.sliceName(), verified.manifestSha256(), verified.manifest());
    }

    private DiagnosticException invalid(ExportRun run, Path path, String message) {
        return failure(ExportDiagnosticCodes.MANIFEST_INVALID, run, path,
                new InvalidSliceException(message));
    }

    private DiagnosticException failure(ExportDiagnosticCodes code,
                                        ExportRun run,
                                        Path path,
                                        Throwable failure) {
        Diagnostic diagnostic = diagnosticFactory.create(code)
                .with("runId", run.runId())
                .with("path", path.toString())
                .with("reason", reason(failure))
                .cause(failure)
                .build();
        diagnosticSink.emit(diagnostic);
        return new DiagnosticException(diagnostic);
    }

    private void emit(ExportDiagnosticCodes code, ExportRun run, Path path, Throwable failure) {
        diagnosticSink.emit(diagnosticFactory.create(code)
                .with("runId", run.runId())
                .with("path", path.toString())
                .with("reason", reason(failure))
                .cause(failure)
                .build());
    }

    private String reason(Throwable failure) {
        return Objects.toString(failure.getMessage(), failure.getClass().getSimpleName());
    }
}
