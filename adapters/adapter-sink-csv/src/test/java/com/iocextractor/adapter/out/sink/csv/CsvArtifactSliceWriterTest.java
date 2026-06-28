package com.iocextractor.adapter.out.sink.csv;

import com.iocextractor.application.artifact.ArtifactRow;
import com.iocextractor.application.export.ArtifactCoverage;
import com.iocextractor.application.export.AvailableSlice;
import com.iocextractor.application.export.ExportArtifactSpec;
import com.iocextractor.application.export.ExportFormat;
import com.iocextractor.application.export.ExportMode;
import com.iocextractor.application.export.ExportPlan;
import com.iocextractor.application.export.ExportProfile;
import com.iocextractor.application.export.ExportRun;
import com.iocextractor.application.export.ExportRunStatus;
import com.iocextractor.application.export.SliceInspectionState;
import com.iocextractor.application.export.SliceManifest;
import com.iocextractor.application.export.SnapshotArtifactMetadata;
import com.iocextractor.application.export.SnapshotMetadata;
import com.iocextractor.application.export.SnapshotRequest;
import com.iocextractor.application.export.StagedSlice;
import com.iocextractor.application.port.out.export.SliceManifestCodec;
import com.iocextractor.application.port.out.export.SnapshotRowConsumer;
import com.iocextractor.application.port.out.export.SnapshotSliceReader;
import com.iocextractor.diagnostics.Diagnostic;
import com.iocextractor.diagnostics.DiagnosticException;
import com.iocextractor.diagnostics.DiagnosticFactory;
import com.iocextractor.diagnostics.codes.ExportDiagnosticCodes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CsvArtifactSliceWriterTest {

    private static final Instant NOW = Instant.parse("2026-06-28T00:00:00Z");
    private static final String HASH_A = "a".repeat(64);
    private static final String HASH_B = "b".repeat(64);
    private static final String HASH_C = "c".repeat(64);

    @TempDir
    Path tempDir;

    @Test
    void stagesDeterministicTreeAndPublishesOnlyByAtomicRename() throws Exception {
        ExportPlan plan = plan();
        ExportRun run = started(plan);
        TestManifestCodec codec = new TestManifestCodec();
        RecordingFileOperations operations = new RecordingFileOperations();
        CsvArtifactSliceWriter writer = writer(tempDir.resolve("one"), codec, operations, new ArrayList<>());

        StagedSlice staged = writer.stage(run, new SnapshotRequest(plan), reader(plan, 2));
        Path staging = tempDir.resolve("one/.staging/run-1");
        Path available = tempDir.resolve("one/complete/slice-1");

        assertThat(Files.readString(staging.resolve("masks.csv"), StandardCharsets.UTF_8))
                .isEqualTo("\"id\";\"value\"\r\n\"1\";\"ioc-1\"\r\n\"2\";\"ioc-2\"\r\n");
        assertThat(Files.list(staging).map(path -> path.getFileName().toString()).toList())
                .containsExactlyInAnyOrder("masks.csv", "hashes.csv", "manifest.json", "_SUCCESS");
        byte[] manifestBytes = Files.readAllBytes(staging.resolve("manifest.json"));
        assertThat(Files.readString(staging.resolve("_SUCCESS"), StandardCharsets.US_ASCII))
                .isEqualTo(SliceHashes.sha256(manifestBytes) + "\n");
        assertThat(staged.manifest().artifacts()).extracting("rows").containsExactly(2L, 2L);
        assertThat(operations.forcedFiles).endsWith("_SUCCESS");
        assertThat(available).doesNotExist();

        ExportRun stagedRun = withStatus(run, ExportRunStatus.STAGED, staged.manifestSha256());
        AvailableSlice published = writer.makeAvailable(stagedRun);

        assertThat(published.manifestSha256()).isEqualTo(staged.manifestSha256());
        assertThat(staging).doesNotExist();
        assertThat(available).isDirectory();
        assertThat(operations.atomicMoves).containsExactly(staging + " -> " + available);
        assertThat(writer.inspect(withStatus(run, ExportRunStatus.AVAILABLE, staged.manifestSha256())).state())
                .isEqualTo(SliceInspectionState.AVAILABLE);
        assertThat(writer.makeAvailable(withStatus(run, ExportRunStatus.AVAILABLE, staged.manifestSha256())))
                .isEqualTo(published);
    }

    @Test
    void producesTheSameBytesForTheSameSnapshotInDifferentRoots() throws Exception {
        ExportPlan plan = plan();
        ExportRun run = started(plan);
        TestManifestCodec codec = new TestManifestCodec();
        CsvArtifactSliceWriter first = new CsvArtifactSliceWriter(tempDir.resolve("first"), codec);
        CsvArtifactSliceWriter second = new CsvArtifactSliceWriter(tempDir.resolve("second"), codec);

        first.stage(run, new SnapshotRequest(plan), reader(plan, 3));
        second.stage(run, new SnapshotRequest(plan), reader(plan, 3));

        Path firstTree = tempDir.resolve("first/.staging/run-1");
        Path secondTree = tempDir.resolve("second/.staging/run-1");
        for (String file : List.of("masks.csv", "hashes.csv", "manifest.json", "_SUCCESS")) {
            assertThat(Files.readAllBytes(firstTree.resolve(file)))
                    .as(file)
                    .containsExactly(Files.readAllBytes(secondTree.resolve(file)));
        }
    }

    @Test
    void detectsCorruptionAndEmitsManifestDiagnosticIdempotently() throws Exception {
        ExportPlan plan = plan();
        ExportRun run = started(plan);
        List<Diagnostic> diagnostics = new ArrayList<>();
        CsvArtifactSliceWriter writer = writer(tempDir, new TestManifestCodec(),
                new RecordingFileOperations(), diagnostics);
        writer.stage(run, new SnapshotRequest(plan), reader(plan, 1));
        Files.writeString(tempDir.resolve(".staging/run-1/masks.csv"), "corrupt",
                StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND);

        assertThat(writer.inspect(run).state()).isEqualTo(SliceInspectionState.CORRUPT);
        assertThat(writer.inspect(run).state()).isEqualTo(SliceInspectionState.CORRUPT);
        assertThat(diagnostics).extracting(diagnostic -> diagnostic.code().id())
                .containsOnly(ExportDiagnosticCodes.MANIFEST_INVALID.id());
    }

    @Test
    void recoversMissingSuccessMarkerWithoutRereadingSnapshot() throws Exception {
        ExportPlan plan = plan();
        ExportRun run = started(plan);
        CsvArtifactSliceWriter writer = new CsvArtifactSliceWriter(tempDir, new TestManifestCodec());
        StagedSlice original = writer.stage(run, new SnapshotRequest(plan), reader(plan, 1));
        Files.delete(tempDir.resolve(".staging/run-1/_SUCCESS"));

        assertThat(writer.inspect(run).state()).isEqualTo(SliceInspectionState.RECOVERABLE);
        StagedSlice recovered = writer.recoverStaging(run);

        assertThat(recovered).isEqualTo(original);
        assertThat(writer.inspect(run).state()).isEqualTo(SliceInspectionState.STAGED);
        assertThat(writer.recoverStaging(run)).isEqualTo(original);
    }

    @Test
    void failsFastWhenFilesystemCannotProvideAtomicMove() {
        ExportPlan plan = plan();
        ExportRun run = started(plan);
        List<Diagnostic> diagnostics = new ArrayList<>();
        RecordingFileOperations operations = new RecordingFileOperations();
        operations.atomicMoveSupported = false;
        CsvArtifactSliceWriter writer = writer(tempDir, new TestManifestCodec(), operations, diagnostics);
        StagedSlice staged = writer.stage(run, new SnapshotRequest(plan), reader(plan, 1));

        assertThatThrownBy(() -> writer.makeAvailable(
                withStatus(run, ExportRunStatus.STAGED, staged.manifestSha256())))
                .isInstanceOf(DiagnosticException.class)
                .satisfies(failure -> assertThat(((DiagnosticException) failure).diagnostic().code())
                        .isEqualTo(ExportDiagnosticCodes.ATOMIC_PUBLISH_UNSUPPORTED));
        assertThat(tempDir.resolve(".staging/run-1")).isDirectory();
        assertThat(tempDir.resolve("complete/slice-1")).doesNotExist();
        assertThat(diagnostics).extracting(diagnostic -> diagnostic.code())
                .containsExactly(ExportDiagnosticCodes.ATOMIC_PUBLISH_UNSUPPORTED);
    }

    @Test
    void streamsManyRowsWithoutAccumulatingThemInTheWriter() {
        ExportPlan oneArtifact = oneArtifactPlan();
        ExportRun run = started(oneArtifact);
        AtomicLong generated = new AtomicLong();
        SnapshotSliceReader reader = streamingReader(oneArtifact, 50_000, generated);
        CsvArtifactSliceWriter writer = new CsvArtifactSliceWriter(tempDir, new TestManifestCodec());

        StagedSlice result = writer.stage(run, new SnapshotRequest(oneArtifact), reader);

        assertThat(generated).hasValue(50_000);
        assertThat(result.manifest().artifacts().getFirst().rows()).isEqualTo(50_000);
        assertThat(tempDir.resolve(".staging/run-1/masks.csv")).isNotEmptyFile();
    }

    @Test
    void classifiesPartialAndConflictingFilesystemStates() throws Exception {
        ExportPlan plan = plan();
        ExportRun run = started(plan);
        CsvArtifactSliceWriter writer = new CsvArtifactSliceWriter(tempDir, new TestManifestCodec());
        Files.createDirectories(tempDir.resolve(".staging/run-1"));
        assertThat(writer.inspect(run).state()).isEqualTo(SliceInspectionState.PARTIAL);

        writer.discardStaging(run);
        StagedSlice staged = writer.stage(run, new SnapshotRequest(plan), reader(plan, 1));
        Files.createDirectories(tempDir.resolve("complete/slice-1"));
        assertThat(writer.inspect(withStatus(run, ExportRunStatus.STAGED, staged.manifestSha256())).state())
                .isEqualTo(SliceInspectionState.CONFLICT);
    }

    @Test
    void validatesCharsetBeforeOpeningArtifactFile() {
        ExportPlan plan = plan(new ExportFormat("csv", "not-a-charset", ";", "\"", "NULL"));
        ExportRun run = started(plan);
        List<Diagnostic> diagnostics = new ArrayList<>();
        CsvArtifactSliceWriter writer = writer(tempDir, new TestManifestCodec(),
                new RecordingFileOperations(), diagnostics);

        assertThatThrownBy(() -> writer.stage(run, new SnapshotRequest(plan), reader(plan, 1)))
                .isInstanceOf(DiagnosticException.class)
                .satisfies(failure -> assertThat(((DiagnosticException) failure).diagnostic().code())
                        .isEqualTo(ExportDiagnosticCodes.SLICE_WRITE_FAILED));
        assertThat(tempDir.resolve(".staging/run-1/masks.csv")).doesNotExist();
        assertThat(diagnostics).extracting(Diagnostic::code)
                .containsExactly(ExportDiagnosticCodes.SLICE_WRITE_FAILED);
    }

    @Test
    void persistsEveryDirectoryEntryWhenCreatingANestedExportRoot() {
        Path root = tempDir.resolve("new-parent/export");
        ExportPlan plan = oneArtifactPlan();
        RecordingFileOperations operations = new RecordingFileOperations();
        CsvArtifactSliceWriter writer = writer(root, new TestManifestCodec(), operations, new ArrayList<>());

        writer.stage(started(plan), new SnapshotRequest(plan), reader(plan, 1));

        assertThat(operations.forcedDirectories)
                .contains(tempDir, tempDir.resolve("new-parent"), root, root.resolve(".staging"));
    }

    private CsvArtifactSliceWriter writer(Path root,
                                          SliceManifestCodec codec,
                                          SliceFileOperations operations,
                                          List<Diagnostic> diagnostics) {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        return new CsvArtifactSliceWriter(root, codec, operations, diagnostics::add,
                new DiagnosticFactory(clock));
    }

    private ExportPlan plan() {
        return plan(new ExportFormat("csv", "UTF-8", ";", "\"", "NULL"));
    }

    private ExportPlan plan(ExportFormat format) {
        ExportArtifactSpec masks = spec("masks", "masks.csv", HASH_A, HASH_B);
        ExportArtifactSpec hashes = spec("hashes", "hashes.csv", HASH_B, HASH_C);
        return new ExportPlan(1,
                new ExportProfile("complete", ExportMode.COMPLETE, List.of("masks", "hashes")),
                format,
                List.of(masks, hashes));
    }

    private ExportPlan oneArtifactPlan() {
        ExportArtifactSpec masks = spec("masks", "masks.csv", HASH_A, HASH_B);
        return new ExportPlan(1,
                new ExportProfile("complete", ExportMode.COMPLETE, List.of("masks")),
                new ExportFormat("csv", "UTF-8", ";", "\"", "NULL"),
                List.of(masks));
    }

    private ExportArtifactSpec spec(String name, String fileName, String identity, String schema) {
        return new ExportArtifactSpec(name, fileName, List.of("id", "value"),
                1, identity, schema, schema);
    }

    private ExportRun started(ExportPlan plan) {
        return ExportRun.started("run-1", plan.profile().name(), "slice-1", plan.planHash(), NOW);
    }

    private ExportRun withStatus(ExportRun run, ExportRunStatus status, String manifestHash) {
        return new ExportRun(run.runId(), run.profile(), status, run.sliceName(), run.planHash(),
                manifestHash, run.startedAt(), run.updatedAt(), null);
    }

    private SnapshotSliceReader reader(ExportPlan plan, int rows) {
        return streamingReader(plan, rows, new AtomicLong());
    }

    private SnapshotSliceReader streamingReader(ExportPlan plan, int rows, AtomicLong generated) {
        List<SnapshotArtifactMetadata> metadata = plan.artifacts().stream()
                .map(spec -> new SnapshotArtifactMetadata(spec.artifactName(), spec.fileName(), spec.columns(),
                        new ArtifactCoverage(1, NOW, rows), spec.identityEpoch(),
                        spec.identityHash(), spec.schemaHash()))
                .toList();
        SnapshotMetadata snapshot = new SnapshotMetadata(plan.profile().name(), plan.planHash(), NOW, metadata);
        return (request, consumer) -> {
            consumer.begin(snapshot);
            for (SnapshotArtifactMetadata artifact : metadata) {
                consumer.beginArtifact(artifact);
                for (int id = 1; id <= rows; id++) {
                    Map<String, String> values = new LinkedHashMap<>();
                    values.put("id", Integer.toString(id));
                    values.put("value", "ioc-" + id);
                    consumer.row(ArtifactRow.ordered(values));
                    generated.incrementAndGet();
                }
                consumer.endArtifact();
            }
            consumer.end();
            return snapshot;
        };
    }

    private static final class TestManifestCodec implements SliceManifestCodec {
        private final Map<String, SliceManifest> decoded = new ConcurrentHashMap<>();

        @Override
        public byte[] encode(SliceManifest manifest) {
            byte[] bytes = manifest.toString().getBytes(StandardCharsets.UTF_8);
            decoded.put(new String(bytes, StandardCharsets.UTF_8), manifest);
            return bytes;
        }

        @Override
        public SliceManifest decode(byte[] bytes) {
            SliceManifest manifest = decoded.get(new String(bytes, StandardCharsets.UTF_8));
            if (manifest == null) {
                throw new IllegalArgumentException("unknown or corrupt manifest bytes");
            }
            return manifest;
        }
    }

    private static final class RecordingFileOperations implements SliceFileOperations {
        private final NioSliceFileOperations delegate = new NioSliceFileOperations();
        private final List<String> forcedFiles = new ArrayList<>();
        private final List<String> atomicMoves = new ArrayList<>();
        private final List<Path> forcedDirectories = new ArrayList<>();
        private boolean atomicMoveSupported = true;

        @Override
        public void forceFile(Path file) throws IOException {
            delegate.forceFile(file);
            forcedFiles.add(file.getFileName().toString());
        }

        @Override
        public void forceDirectory(Path directory) throws IOException {
            delegate.forceDirectory(directory);
            forcedDirectories.add(directory);
        }

        @Override
        public void moveAtomically(Path source, Path target) throws IOException {
            if (!atomicMoveSupported) {
                throw new AtomicMoveNotSupportedException(source.toString(), target.toString(), "test filesystem");
            }
            delegate.moveAtomically(source, target);
            atomicMoves.add(source + " -> " + target);
        }
    }
}
