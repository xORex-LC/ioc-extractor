package com.iocextractor.adapter.out.sink.csv;

import com.iocextractor.application.export.ArtifactCoverage;
import com.iocextractor.application.export.ExportFormat;
import com.iocextractor.application.export.ExportMode;
import com.iocextractor.application.export.SliceArtifactManifest;
import com.iocextractor.application.export.SliceManifest;
import com.iocextractor.application.port.out.export.SliceManifestCodec;
import com.iocextractor.common.IocExtractorException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileSystemSliceRetentionStoreTest {

    private static final Instant CREATED = Instant.parse("2026-06-28T00:00:00Z");
    private static final String HASH = "a".repeat(64);

    @TempDir
    Path tempDir;

    @Test
    void listsOnlyVerifiedFinalSlicesAndDeletesDirectoryAsUnit() throws Exception {
        TestCodec codec = new TestCodec();
        Path slice = createSlice(codec, "reputation", "slice-one", true);
        Files.createDirectories(tempDir.resolve(".staging/partial"));
        Files.writeString(tempDir.resolve(".staging/partial/data.tmp"), "partial");
        var store = new FileSystemSliceRetentionStore(tempDir, codec);

        var listed = store.listCompleted("reputation");

        assertThat(listed).singleElement().satisfies(descriptor -> {
            assertThat(descriptor.sliceId()).isEqualTo("slice-one");
            assertThat(descriptor.sliceName()).isEqualTo("slice-one");
            assertThat(descriptor.createdAt()).isEqualTo(CREATED);
        });
        store.delete(listed.getFirst());
        assertThat(slice).doesNotExist();
        assertThat(tempDir.resolve(".staging/partial/data.tmp")).exists();
    }

    @Test
    void corruptFinalFailsSweepAndRemainsUntouched() throws Exception {
        TestCodec codec = new TestCodec();
        Path slice = createSlice(codec, "reputation", "corrupt", true);
        Files.writeString(slice.resolve("masks.csv"), "tampered", StandardCharsets.UTF_8);
        var store = new FileSystemSliceRetentionStore(tempDir, codec);

        assertThatThrownBy(() -> store.listCompleted("reputation"))
                .isInstanceOf(IocExtractorException.class)
                .hasMessageContaining("verified export slices");
        assertThat(slice).isDirectory();
    }

    @Test
    void incompleteFinalWithoutSuccessMarkerIsNotADeletionCandidate() throws Exception {
        TestCodec codec = new TestCodec();
        Path slice = createSlice(codec, "reputation", "incomplete", false);
        var store = new FileSystemSliceRetentionStore(tempDir, codec);

        assertThatThrownBy(() -> store.listCompleted("reputation"))
                .isInstanceOf(IocExtractorException.class);
        assertThat(slice).isDirectory();
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
