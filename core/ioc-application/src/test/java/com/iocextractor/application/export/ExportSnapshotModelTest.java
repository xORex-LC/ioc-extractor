package com.iocextractor.application.export;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExportSnapshotModelTest {

    private static final Instant NOW = Instant.parse("2026-06-28T00:00:00Z");
    private static final String HASH_A = "a".repeat(64);
    private static final String HASH_B = "b".repeat(64);

    @Test
    void coverage_requires_timestamp_exactly_when_revision_is_positive() {
        assertThat(ArtifactCoverage.empty()).isEqualTo(new ArtifactCoverage(0, null, 0));
        assertThatThrownBy(() -> new ArtifactCoverage(0, NOW, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ArtifactCoverage(1, null, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void snapshot_and_manifest_copy_artifact_lists() {
        var snapshotArtifacts = new ArrayList<>(List.of(snapshotArtifact()));
        SnapshotMetadata snapshot = new SnapshotMetadata("default", HASH_A, NOW, snapshotArtifacts);
        snapshotArtifacts.clear();

        var manifestArtifacts = new ArrayList<>(List.of(manifestArtifact("masks")));
        SliceManifest manifest = new SliceManifest(
                1, "run-1", "run-1", "default", NOW, ExportMode.COMPLETE,
                HASH_A, new ExportFormat("csv", "UTF-8", ";", "\"", "NULL"), manifestArtifacts);
        manifestArtifacts.clear();

        assertThat(snapshot.artifacts()).hasSize(1);
        assertThat(manifest.artifacts()).hasSize(1);
        assertThatThrownBy(() -> manifest.artifacts().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void manifest_rejects_duplicate_artifacts_and_mismatched_slice_id() {
        assertThatThrownBy(() -> new SliceManifest(
                1, "slice-1", "run-1", "default", NOW, ExportMode.COMPLETE,
                HASH_A, format(), List.of(manifestArtifact("masks"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must equal run id");

        assertThatThrownBy(() -> new SliceManifest(
                1, "run-1", "run-1", "default", NOW, ExportMode.COMPLETE,
                HASH_A, format(), List.of(manifestArtifact("masks"), manifestArtifact("masks"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unique");
    }

    @Test
    void artifactSpecRejectsMissingOrAmbiguousColumns() {
        assertThatThrownBy(() -> artifactSpec("masks.csv", List.of(), 1, HASH_A))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-empty names");
        assertThatThrownBy(() -> artifactSpec(
                "masks.csv", Arrays.asList("id", null), 1, HASH_A))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> artifactSpec("masks.csv", List.of("id", " "), 1, HASH_A))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-empty names");
        assertThatThrownBy(() -> artifactSpec("masks.csv", List.of("id", "id"), 1, HASH_A))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unique");
    }

    @Test
    void artifactSpecRequiresALeafFileName() {
        for (String fileName : List.of("nested/masks.csv", "nested\\masks.csv", ".", "..")) {
            assertThatThrownBy(() -> artifactSpec(fileName, List.of("id"), 1, HASH_A))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("single relative path segment");
        }
        assertThatThrownBy(() -> artifactSpec(" ", List.of("id"), 1, HASH_A))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fileName");
    }

    @Test
    void artifactSpecRequiresVersionedLowerCaseIdentityHashes() {
        assertThatThrownBy(() -> artifactSpec("masks.csv", List.of("id"), 0, HASH_A))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("identity epoch");
        assertThatThrownBy(() -> artifactSpec("masks.csv", List.of("id"), 1, "A".repeat(64)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lower-case SHA-256");
        assertThatThrownBy(() -> new ExportArtifactSpec(
                " ", "masks.csv", List.of("id"), 1, HASH_A, HASH_A, HASH_A))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("artifactName");
        assertThatThrownBy(() -> new ExportArtifactSpec(
                "masks", "masks.csv", List.of("id"), 1, HASH_A, null, HASH_A))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("schemaHash");
    }

    private SnapshotArtifactMetadata snapshotArtifact() {
        return new SnapshotArtifactMetadata(
                "masks", "masks.csv", List.of("id", "mask"),
                new ArtifactCoverage(1, NOW, 2), 1, HASH_A, HASH_B);
    }

    private SliceArtifactManifest manifestArtifact(String name) {
        return new SliceArtifactManifest(
                name, name + ".csv", 2, new ArtifactCoverage(1, NOW, 2),
                1, HASH_A, HASH_B, HASH_A);
    }

    private ExportArtifactSpec artifactSpec(
            String fileName,
            List<String> columns,
            int identityEpoch,
            String identityHash) {
        return new ExportArtifactSpec(
                "masks", fileName, columns, identityEpoch, identityHash, HASH_A, HASH_B);
    }

    private ExportFormat format() {
        return new ExportFormat("csv", "UTF-8", ";", "\"", "NULL");
    }
}
