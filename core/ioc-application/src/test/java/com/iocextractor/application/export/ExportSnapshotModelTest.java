package com.iocextractor.application.export;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
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

    private ExportFormat format() {
        return new ExportFormat("csv", "UTF-8", ";", "\"", "NULL");
    }
}
