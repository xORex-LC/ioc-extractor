package com.iocextractor.adapter.out.sink.csv;

import com.iocextractor.application.export.ArtifactCoverage;
import com.iocextractor.application.export.ExportFormat;
import com.iocextractor.application.export.ExportMode;
import com.iocextractor.application.export.SliceArtifactManifest;
import com.iocextractor.application.export.SliceManifest;
import com.iocextractor.application.port.out.export.SliceManifestCodec;
import com.iocextractor.diagnostics.Diagnostic;
import com.iocextractor.diagnostics.DiagnosticFactory;
import com.iocextractor.diagnostics.codes.SyncDiagnosticCodes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileSystemCompletedSliceCatalogTest {

    private static final Instant CREATED = Instant.parse("2026-06-28T00:00:00Z");
    private static final String HASH = "a".repeat(64);
    private static final Clock CLOCK = Clock.fixed(CREATED, ZoneOffset.UTC);

    @TempDir
    Path tempDir;

    @Test
    void listsVerifiedCompletedSlicesByProfile() throws Exception {
        TestCodec codec = new TestCodec();
        Path slice = createSlice(codec, "reputation", "slice-one", true);
        createSlice(codec, "archive", "slice-two", true);
        var catalog = new FileSystemCompletedSliceCatalog(tempDir, codec);

        var completed = catalog.listCompleted("reputation");

        assertThat(completed).singleElement().satisfies(item -> {
            assertThat(item.sliceId()).isEqualTo("slice-one");
            assertThat(item.profile()).isEqualTo("reputation");
            assertThat(item.sliceName()).isEqualTo("slice-one");
            assertThat(item.directory()).isEqualTo(slice.toAbsolutePath().normalize());
            assertThat(item.manifest().createdAt()).isEqualTo(CREATED);
        });
        assertThat(catalog.listCompleted("archive"))
                .singleElement()
                .extracting(item -> item.sliceId())
                .isEqualTo("slice-two");
    }

    @Test
    void missingProfileReturnsEmptyWorklist() {
        var catalog = new FileSystemCompletedSliceCatalog(tempDir, new TestCodec());

        assertThat(catalog.listCompleted("missing")).isEmpty();
        assertThat(catalog.find("missing", "slice-one")).isEmpty();
    }

    @Test
    void findsOneCompletedSliceByNameWithoutScanningSiblings() throws Exception {
        TestCodec codec = new TestCodec();
        Path wanted = createSlice(codec, "reputation", "slice-one", true);
        Path corrupt = createSlice(codec, "reputation", "corrupt", true);
        Files.writeString(corrupt.resolve("masks.csv"), "tampered", StandardCharsets.UTF_8);
        List<Diagnostic> diagnostics = new ArrayList<>();
        var catalog = catalog(codec, diagnostics);

        assertThat(catalog.find("reputation", "slice-one"))
                .hasValueSatisfying(item -> {
                    assertThat(item.sliceId()).isEqualTo("slice-one");
                    assertThat(item.directory()).isEqualTo(wanted.toAbsolutePath().normalize());
                });
        assertThat(catalog.listCompleted("reputation"))
                .singleElement()
                .extracting(item -> item.sliceId())
                .isEqualTo("slice-one");
        assertThat(diagnostics).singleElement().satisfies(diagnostic -> {
            assertThat(diagnostic.code()).isEqualTo(SyncDiagnosticCodes.LOCAL_SLICE_INVALID);
            assertThat(diagnostic.context())
                    .containsEntry("profile", "reputation")
                    .containsEntry("sliceName", "corrupt");
        });
    }

    @Test
    void corruptFinalSliceIsSkippedAndDiagnosed() throws Exception {
        TestCodec codec = new TestCodec();
        Path slice = createSlice(codec, "reputation", "corrupt", true);
        Files.writeString(slice.resolve("masks.csv"), "tampered", StandardCharsets.UTF_8);
        List<Diagnostic> diagnostics = new ArrayList<>();
        var catalog = catalog(codec, diagnostics);

        assertThat(catalog.listCompleted("reputation")).isEmpty();
        assertThat(diagnostics).singleElement().satisfies(diagnostic -> {
            assertThat(diagnostic.code()).isEqualTo(SyncDiagnosticCodes.LOCAL_SLICE_INVALID);
            assertThat(diagnostic.context()).containsEntry("sliceName", slice.getFileName().toString());
        });
    }

    @Test
    void incompleteFinalSliceDoesNotBecomePendingWork() throws Exception {
        TestCodec codec = new TestCodec();
        createSlice(codec, "reputation", "incomplete", false);
        List<Diagnostic> diagnostics = new ArrayList<>();
        var catalog = catalog(codec, diagnostics);

        assertThat(catalog.listCompleted("reputation")).isEmpty();
        assertThat(diagnostics).singleElement().satisfies(diagnostic -> {
            assertThat(diagnostic.code()).isEqualTo(SyncDiagnosticCodes.LOCAL_SLICE_INVALID);
            assertThat(diagnostic.context()).containsEntry("sliceName", "incomplete");
        });
    }

    @Test
    void rejectsUnsafeProfileAndNonDirectorySliceMembers() throws Exception {
        TestCodec codec = new TestCodec();
        Files.createDirectories(tempDir.resolve("reputation"));
        Files.writeString(tempDir.resolve("reputation/not-a-slice"), "data");
        var catalog = new FileSystemCompletedSliceCatalog(tempDir, codec);

        assertThatThrownBy(() -> catalog.listCompleted("../reputation"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("profile");
        assertThat(catalog.listCompleted("reputation")).isEmpty();
    }

    @Test
    void stagingTreeIsOutsidePublishWorklist() throws Exception {
        TestCodec codec = new TestCodec();
        Files.createDirectories(tempDir.resolve(".staging/partial"));
        Files.writeString(tempDir.resolve(".staging/partial/data.tmp"), "partial");
        var catalog = new FileSystemCompletedSliceCatalog(tempDir, codec);

        assertThat(catalog.listCompleted("reputation")).isEmpty();
        assertThat(tempDir.resolve(".staging/partial/data.tmp")).exists();
    }

    private Path createSlice(TestCodec codec,
                             String profile,
                             String sliceId,
                             boolean success) throws Exception {
        Path directory = tempDir.resolve(profile).resolve(sliceId);
        Files.createDirectories(directory);
        byte[] data = "id;mask\n1;example.org\n".getBytes(StandardCharsets.UTF_8);
        Files.write(directory.resolve("masks.csv"), data);
        SliceManifest manifest = new SliceManifest(
                1, sliceId, sliceId, profile, CREATED, ExportMode.COMPLETE,
                HASH, new ExportFormat("csv", "UTF-8", ";", "\"", "NULL"),
                List.of(new SliceArtifactManifest(
                        "masks", "masks.csv", 1, ArtifactCoverage.empty(), 1,
                        HASH, HASH, SliceHashes.sha256(data))));
        byte[] manifestBytes = codec.encode(manifest);
        Files.write(directory.resolve("manifest.json"), manifestBytes);
        if (success) {
            Files.writeString(directory.resolve("_SUCCESS"),
                    SliceHashes.sha256(manifestBytes) + "\n", StandardCharsets.US_ASCII);
        }
        return directory;
    }

    private FileSystemCompletedSliceCatalog catalog(TestCodec codec, List<Diagnostic> diagnostics) {
        return new FileSystemCompletedSliceCatalog(
                tempDir, codec, diagnostics::add, new DiagnosticFactory(CLOCK));
    }

    private static final class TestCodec implements SliceManifestCodec {
        private final Map<String, SliceManifest> manifests = new ConcurrentHashMap<>();

        @Override
        public byte[] encode(SliceManifest manifest) {
            byte[] bytes = manifest.toString().getBytes(StandardCharsets.UTF_8);
            manifests.put(new String(bytes, StandardCharsets.UTF_8), manifest);
            return bytes;
        }

        @Override
        public SliceManifest decode(byte[] bytes) {
            SliceManifest manifest = manifests.get(new String(bytes, StandardCharsets.UTF_8));
            if (manifest == null) {
                throw new IllegalArgumentException("unknown manifest");
            }
            return manifest;
        }
    }
}
