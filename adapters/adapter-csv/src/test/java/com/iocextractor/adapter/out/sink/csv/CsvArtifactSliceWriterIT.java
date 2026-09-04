package com.iocextractor.adapter.out.sink.csv;

import com.iocextractor.application.tck.junit.IntegrationTest;
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

@IntegrationTest
class CsvArtifactSliceWriterIT {

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
    void rejectsRunPlanIdentityDriftAndReservedOrDuplicateArtifactNamesBeforeWriting() {
        ExportPlan valid = plan();
        CsvArtifactSliceWriter writer = new CsvArtifactSliceWriter(tempDir, new TestManifestCodec());
        ExportRun started = started(valid);

        assertThatThrownBy(() -> writer.stage(
                withStatus(started, ExportRunStatus.STAGED, HASH_A),
                new SnapshotRequest(valid), reader(valid, 0)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("only a STARTED export run can be staged");
        assertThatThrownBy(() -> writer.stage(
                ExportRun.started("profile-drift", "other", "slice-profile", valid.planHash(), NOW),
                new SnapshotRequest(valid), reader(valid, 0)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("export request plan does not match run identity");
        assertThatThrownBy(() -> writer.stage(
                ExportRun.started("hash-drift", valid.profile().name(), "slice-hash", HASH_C, NOW),
                new SnapshotRequest(valid), reader(valid, 0)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("export request plan does not match run identity");

        List<List<String>> invalidFileNames = List.of(
                List.of("duplicate.csv", "duplicate.csv"),
                List.of("manifest.json", "other.csv"),
                List.of("other.csv", "_SUCCESS"));
        for (int index = 0; index < invalidFileNames.size(); index++) {
            ExportPlan invalid = planWithFileNames(invalidFileNames.get(index));
            ExportRun run = started(invalid, "invalid-files-" + index, "invalid-slice-" + index);

            assertThatThrownBy(() -> writer.stage(
                    run, new SnapshotRequest(invalid), reader(invalid, 0)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("export artifact file names must be unique and non-reserved");
        }

        assertThat(tempDir).isEmptyDirectory();
    }

    @Test
    void refusesToOverwriteEitherStagingOrPublishedSliceIdentity() {
        ExportPlan plan = plan();
        ExportRun run = started(plan);
        CsvArtifactSliceWriter writer = new CsvArtifactSliceWriter(tempDir, new TestManifestCodec());
        StagedSlice staged = writer.stage(run, new SnapshotRequest(plan), reader(plan, 0));

        assertThatThrownBy(() -> writer.stage(run, new SnapshotRequest(plan), reader(plan, 0)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("slice path already exists; inspect/recover before staging");

        writer.makeAvailable(withStatus(run, ExportRunStatus.STAGED, staged.manifestSha256()));
        assertThatThrownBy(() -> writer.stage(run, new SnapshotRequest(plan), reader(plan, 0)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("slice path already exists; inspect/recover before staging");
    }

    @Test
    void rejectsSnapshotReaderThatReturnsWithoutEndingMaterialization() {
        ExportPlan plan = oneArtifactPlan();
        ExportRun run = started(plan);
        SnapshotMetadata snapshot = snapshot(plan, 0);
        SnapshotSliceReader incomplete = (request, consumer) -> {
            consumer.begin(snapshot);
            consumer.beginArtifact(snapshot.artifacts().getFirst());
            consumer.endArtifact();
            return snapshot;
        };

        CsvArtifactSliceWriter writer = new CsvArtifactSliceWriter(tempDir, new TestManifestCodec());

        assertThatThrownBy(() -> writer.stage(run, new SnapshotRequest(plan), incomplete))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("snapshot reader returned before end callback");
        assertThat(tempDir.resolve(".staging/run-1/manifest.json")).doesNotExist();
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

    @Test
    void callbackFailureDoesNotContaminateTheNextStageOperation() {
        ExportPlan plan = oneArtifactPlan();
        CsvArtifactSliceWriter writer = new CsvArtifactSliceWriter(tempDir, new TestManifestCodec());
        ExportRun failedRun = started(plan, "failed-run", "failed-slice");
        SnapshotMetadata failedSnapshot = snapshot(plan, 0);
        SnapshotSliceReader failingReader = (request, consumer) -> {
            consumer.begin(failedSnapshot);
            throw new IllegalStateException("snapshot reader failed");
        };

        assertThatThrownBy(() -> writer.stage(failedRun, new SnapshotRequest(plan), failingReader))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("snapshot reader failed");

        ExportRun successfulRun = started(plan, "successful-run", "successful-slice");
        StagedSlice staged = writer.stage(successfulRun, new SnapshotRequest(plan), reader(plan, 1));

        assertThat(staged.sliceId()).isEqualTo("successful-run");
        assertThat(tempDir.resolve(".staging/successful-run/_SUCCESS")).isRegularFile();
    }

    @Test
    void materializationRejectsSnapshotMetadataOutsideTheRunAndPlanIdentity() throws Exception {
        ExportPlan plan = plan();
        ExportRun run = started(plan);

        try (CsvSliceMaterialization materialization = materialization(
                plan, run, tempDir.resolve("identity"), new TestManifestCodec(), new RecordingFileOperations())) {
            assertThatThrownBy(() -> materialization.begin(new SnapshotMetadata(
                    "other-profile", plan.planHash(), NOW, snapshot(plan, 0).artifacts())))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("snapshot profile differs from export plan/run");
            assertThatThrownBy(() -> materialization.begin(new SnapshotMetadata(
                    plan.profile().name(), HASH_C, NOW, snapshot(plan, 0).artifacts())))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("snapshot plan hash differs from export plan/run");
            assertThatThrownBy(() -> materialization.begin(new SnapshotMetadata(
                    plan.profile().name(), plan.planHash(), NOW,
                    List.of(snapshot(plan, 0).artifacts().getFirst()))))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("snapshot artifact count differs from export plan");
        }
    }

    @Test
    void materializationRejectsOutOfSequenceAndNullCallbacks() throws Exception {
        ExportPlan plan = oneArtifactPlan();
        ExportRun run = started(plan);
        SnapshotMetadata snapshot = snapshot(plan, 0);

        try (CsvSliceMaterialization materialization = materialization(
                plan, run, tempDir.resolve("sequence"), new TestManifestCodec(), new RecordingFileOperations())) {
            assertThatThrownBy(() -> materialization.row(null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("row callback is out of sequence or null");
            assertThatThrownBy(() -> materialization.begin(null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("snapshot begin callback is duplicated or null");

            materialization.begin(snapshot);

            assertThatThrownBy(() -> materialization.begin(snapshot))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("snapshot begin callback is duplicated or null");
            assertThatThrownBy(() -> materialization.beginArtifact(null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("artifact begin callback is out of sequence");
            assertThatThrownBy(materialization::endArtifact)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("artifact end callback is out of sequence");
            assertThatThrownBy(materialization::end)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("snapshot end callback is out of sequence");

            materialization.beginArtifact(snapshot.artifacts().getFirst());
            assertThatThrownBy(() -> materialization.beginArtifact(snapshot.artifacts().getFirst()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("artifact begin callback is out of sequence");
            assertThatThrownBy(() -> materialization.row(null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("row callback is out of sequence or null");
            materialization.endArtifact();
            materialization.end();

            assertThat(materialization.ended()).isTrue();
            assertThatThrownBy(() -> materialization.beginArtifact(snapshot.artifacts().getFirst()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("artifact begin callback is out of sequence");
            assertThatThrownBy(materialization::end)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("snapshot end callback is out of sequence");
        }
    }

    @Test
    void materializationRejectsEveryOrderedArtifactMetadataMismatch() throws Exception {
        ExportPlan plan = oneArtifactPlan();
        ExportArtifactSpec expected = plan.artifacts().getFirst();
        List<SnapshotArtifactMetadata> mismatches = List.of(
                artifactMetadata("other", expected.fileName(), expected.columns(),
                        expected.identityEpoch(), expected.identityHash(), expected.schemaHash()),
                artifactMetadata(expected.artifactName(), "other.csv", expected.columns(),
                        expected.identityEpoch(), expected.identityHash(), expected.schemaHash()),
                artifactMetadata(expected.artifactName(), expected.fileName(), List.of("other"),
                        expected.identityEpoch(), expected.identityHash(), expected.schemaHash()),
                artifactMetadata(expected.artifactName(), expected.fileName(), expected.columns(),
                        expected.identityEpoch() + 1, expected.identityHash(), expected.schemaHash()),
                artifactMetadata(expected.artifactName(), expected.fileName(), expected.columns(),
                        expected.identityEpoch(), HASH_C, expected.schemaHash()),
                artifactMetadata(expected.artifactName(), expected.fileName(), expected.columns(),
                        expected.identityEpoch(), expected.identityHash(), HASH_C));

        for (int index = 0; index < mismatches.size(); index++) {
            SnapshotArtifactMetadata mismatch = mismatches.get(index);
            Path staging = tempDir.resolve("metadata-" + index);
            try (CsvSliceMaterialization materialization = materialization(
                    plan, started(plan), staging, new TestManifestCodec(), new RecordingFileOperations())) {
                materialization.begin(new SnapshotMetadata(
                        plan.profile().name(), plan.planHash(), NOW, List.of(mismatch)));

                assertThatThrownBy(() -> materialization.beginArtifact(mismatch))
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessage("snapshot artifact metadata differs from ordered export plan");
            }
        }
    }

    @Test
    void materializationRejectsUnsupportedCsvFormatsAndUnmappableRows() throws Exception {
        List<ExportFormat> invalidFormats = List.of(
                new ExportFormat("json", "UTF-8", ";", "\"", "NULL"),
                new ExportFormat("csv", "UTF-8", ";;", "\"", "NULL"),
                new ExportFormat("csv", "UTF-8", ";", "\"\"", "NULL"));
        for (int index = 0; index < invalidFormats.size(); index++) {
            ExportPlan plan = plan(invalidFormats.get(index));
            SnapshotMetadata snapshot = snapshot(plan, 0);
            try (CsvSliceMaterialization materialization = materialization(
                    plan, started(plan), tempDir.resolve("format-" + index),
                    new TestManifestCodec(), new RecordingFileOperations())) {
                materialization.begin(snapshot);

                assertThatThrownBy(() -> materialization.beginArtifact(snapshot.artifacts().getFirst()))
                        .isInstanceOf(SliceWriteException.class)
                        .hasMessageStartingWith("cannot open artifact");
            }
        }

        ExportPlan asciiPlan = plan(new ExportFormat("csv", "US-ASCII", ";", "\"", "NULL"));
        SnapshotMetadata asciiSnapshot = snapshot(asciiPlan, 0);
        try (CsvSliceMaterialization materialization = materialization(
                asciiPlan, started(asciiPlan), tempDir.resolve("ascii"),
                new TestManifestCodec(), new RecordingFileOperations())) {
            materialization.begin(asciiSnapshot);
            materialization.beginArtifact(asciiSnapshot.artifacts().getFirst());
            materialization.row(ArtifactRow.ordered(Map.of("id", "1", "value", "кириллица")));

            assertThatThrownBy(materialization::endArtifact)
                    .isInstanceOf(SliceWriteException.class)
                    .hasMessageStartingWith("cannot finish artifact");
        }
    }

    @Test
    void materializationSurfacesDurabilityAndManifestCodecFailures() throws Exception {
        ExportPlan plan = oneArtifactPlan();
        SnapshotMetadata snapshot = snapshot(plan, 0);
        RecordingFileOperations failingOperations = new RecordingFileOperations();
        failingOperations.forceFileFailure = new IOException("force failed");
        try (CsvSliceMaterialization materialization = materialization(
                plan, started(plan), tempDir.resolve("force"), new TestManifestCodec(), failingOperations)) {
            materialization.begin(snapshot);
            materialization.beginArtifact(snapshot.artifacts().getFirst());

            assertThatThrownBy(materialization::endArtifact)
                    .isInstanceOf(SliceWriteException.class)
                    .hasMessageStartingWith("cannot finish artifact")
                    .hasRootCauseMessage("force failed");
        }

        SliceManifestCodec failingCodec = new SliceManifestCodec() {
            @Override
            public byte[] encode(SliceManifest manifest) {
                throw new IllegalStateException("codec failed");
            }

            @Override
            public SliceManifest decode(byte[] bytes) {
                throw new UnsupportedOperationException();
            }
        };
        try (CsvSliceMaterialization materialization = materialization(
                plan, started(plan), tempDir.resolve("codec"), failingCodec, new RecordingFileOperations())) {
            materialization.begin(snapshot);
            materialization.beginArtifact(snapshot.artifacts().getFirst());
            materialization.endArtifact();

            assertThatThrownBy(materialization::end)
                    .isInstanceOf(SliceWriteException.class)
                    .hasMessage("cannot finish slice")
                    .hasRootCauseMessage("codec failed");
        }
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

    private ExportPlan planWithFileNames(List<String> fileNames) {
        ExportArtifactSpec masks = spec("masks", fileNames.get(0), HASH_A, HASH_B);
        ExportArtifactSpec hashes = spec("hashes", fileNames.get(1), HASH_B, HASH_C);
        return new ExportPlan(1,
                new ExportProfile("complete", ExportMode.COMPLETE, List.of("masks", "hashes")),
                new ExportFormat("csv", "UTF-8", ";", "\"", "NULL"),
                List.of(masks, hashes));
    }

    private ExportArtifactSpec spec(String name, String fileName, String identity, String schema) {
        return new ExportArtifactSpec(name, fileName, List.of("id", "value"),
                1, identity, schema, schema);
    }

    private ExportRun started(ExportPlan plan) {
        return started(plan, "run-1", "slice-1");
    }

    private ExportRun started(ExportPlan plan, String runId, String sliceName) {
        return ExportRun.started(runId, plan.profile().name(), sliceName, plan.planHash(), NOW);
    }

    private ExportRun withStatus(ExportRun run, ExportRunStatus status, String manifestHash) {
        return new ExportRun(run.runId(), run.profile(), status, run.sliceName(), run.planHash(),
                manifestHash, run.startedAt(), run.updatedAt(), null);
    }

    private SnapshotSliceReader reader(ExportPlan plan, int rows) {
        return streamingReader(plan, rows, new AtomicLong());
    }

    private SnapshotSliceReader streamingReader(ExportPlan plan, int rows, AtomicLong generated) {
        SnapshotMetadata snapshot = snapshot(plan, rows);
        return (request, consumer) -> {
            consumer.begin(snapshot);
            for (SnapshotArtifactMetadata artifact : snapshot.artifacts()) {
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

    private SnapshotMetadata snapshot(ExportPlan plan, int rows) {
        List<SnapshotArtifactMetadata> metadata = plan.artifacts().stream()
                .map(spec -> new SnapshotArtifactMetadata(spec.artifactName(), spec.fileName(), spec.columns(),
                        new ArtifactCoverage(1, NOW, rows), spec.identityEpoch(),
                        spec.identityHash(), spec.schemaHash()))
                .toList();
        return new SnapshotMetadata(plan.profile().name(), plan.planHash(), NOW, metadata);
    }

    private SnapshotArtifactMetadata artifactMetadata(String artifactName,
                                                      String fileName,
                                                      List<String> columns,
                                                      int identityEpoch,
                                                      String identityHash,
                                                      String schemaHash) {
        return new SnapshotArtifactMetadata(artifactName, fileName, columns,
                new ArtifactCoverage(1, NOW, 0), identityEpoch, identityHash, schemaHash);
    }

    private CsvSliceMaterialization materialization(ExportPlan plan,
                                                    ExportRun run,
                                                    Path staging,
                                                    SliceManifestCodec codec,
                                                    SliceFileOperations operations) throws IOException {
        Files.createDirectories(staging);
        return new CsvSliceMaterialization(run, plan, staging, codec, operations);
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
        private IOException forceFileFailure;

        @Override
        public void forceFile(Path file) throws IOException {
            if (forceFileFailure != null) {
                throw forceFileFailure;
            }
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
