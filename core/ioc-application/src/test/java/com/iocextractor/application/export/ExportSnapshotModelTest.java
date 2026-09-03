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

    @Test
    void export_profile_requires_an_ordered_unique_artifact_set() {
        ExportProfile profile = new ExportProfile(
                "default", ExportMode.COMPLETE, List.of("masks", "hashes"));
        assertThat(profile.artifacts()).containsExactly("masks", "hashes");

        assertThatThrownBy(() -> new ExportProfile(" ", ExportMode.COMPLETE, List.of("masks")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("profile name");
        assertThatThrownBy(() -> new ExportProfile("default", null, List.of("masks")))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("mode");
        assertThatThrownBy(() -> new ExportProfile("default", ExportMode.COMPLETE, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-empty names");
        assertThatThrownBy(() -> new ExportProfile(
                "default", ExportMode.COMPLETE, Arrays.asList("masks", null)))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ExportProfile(
                "default", ExportMode.COMPLETE, List.of("masks", " ")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-empty names");
        assertThatThrownBy(() -> new ExportProfile(
                "default", ExportMode.COMPLETE, List.of("masks", "masks")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unique");
    }

    @Test
    void revision_and_coverage_require_non_negative_consistent_change_markers() {
        assertThat(new ArtifactRevision("masks", 1, NOW).revision()).isEqualTo(1);
        assertThatThrownBy(() -> new ArtifactRevision("masks", -1, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("revision");
        assertThatThrownBy(() -> new ArtifactRevision("masks", 0, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not have changedAt");
        assertThatThrownBy(() -> new ArtifactRevision("masks", 1, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requires changedAt");

        assertThatThrownBy(() -> new ArtifactCoverage(-1, null, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("negative");
        assertThatThrownBy(() -> new ArtifactCoverage(0, null, -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("negative");
    }

    @Test
    void slice_inspection_requires_state_specific_integrity_evidence() {
        SliceManifest manifest = manifest();
        assertThat(new SliceInspection(
                "run-1", SliceInspectionState.RECOVERABLE, HASH_A, manifest, null).manifest())
                .isEqualTo(manifest);
        assertThat(new SliceInspection(
                "run-1", SliceInspectionState.MISSING, null, null, null).state())
                .isEqualTo(SliceInspectionState.MISSING);

        for (SliceInspectionState state : List.of(
                SliceInspectionState.RECOVERABLE,
                SliceInspectionState.STAGED,
                SliceInspectionState.AVAILABLE)) {
            assertThatThrownBy(() -> new SliceInspection(
                    "run-1", state, null, manifest, null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessage("manifestSha256");
            assertThatThrownBy(() -> new SliceInspection(
                    "run-1", state, HASH_A, null, null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessage("manifest");
        }
        for (SliceInspectionState state : List.of(
                SliceInspectionState.CORRUPT, SliceInspectionState.CONFLICT)) {
            assertThatThrownBy(() -> new SliceInspection("run-1", state, null, null, " "))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("requires a reason");
        }
    }

    @Test
    void manifest_requires_versioned_non_empty_integrity_entries() {
        assertThatThrownBy(() -> new SliceManifest(
                0, "run-1", "run-1", "default", NOW, ExportMode.COMPLETE,
                HASH_A, format(), List.of(manifestArtifact("masks"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("version");
        assertThatThrownBy(() -> new SliceManifest(
                1, "run-1", "run-1", "default", NOW, ExportMode.COMPLETE,
                HASH_A, format(), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least one artifact");

        assertThatThrownBy(() -> new SliceArtifactManifest(
                "masks", "masks.csv", -1, new ArtifactCoverage(0, null, 0),
                1, HASH_A, HASH_A, HASH_A))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("row count");
        assertThatThrownBy(() -> new SliceArtifactManifest(
                "masks", "masks.csv", 0, new ArtifactCoverage(0, null, 0),
                0, HASH_A, HASH_A, HASH_A))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("identity epoch");
        assertThatThrownBy(() -> new SliceArtifactManifest(
                "masks", "masks.csv", 0, null, 1, HASH_A, HASH_A, HASH_A))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("coverage");
        assertThatThrownBy(() -> new SliceArtifactManifest(
                "masks", "masks.csv", 0, new ArtifactCoverage(0, null, 0),
                1, HASH_A, HASH_A, "invalid"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sha256");
    }

    private static SnapshotArtifactMetadata snapshotArtifact() {
        return new SnapshotArtifactMetadata(
                "masks", "masks.csv", List.of("id", "mask"),
                new ArtifactCoverage(1, NOW, 2), 1, HASH_A, HASH_B);
    }

    private static SliceArtifactManifest manifestArtifact(String name) {
        return new SliceArtifactManifest(
                name, name + ".csv", 2, new ArtifactCoverage(1, NOW, 2),
                1, HASH_A, HASH_B, HASH_A);
    }

    private static ExportArtifactSpec artifactSpec(
            String fileName,
            List<String> columns,
            int identityEpoch,
            String identityHash) {
        return new ExportArtifactSpec(
                "masks", fileName, columns, identityEpoch, identityHash, HASH_A, HASH_B);
    }

    private static ExportFormat format() {
        return new ExportFormat("csv", "UTF-8", ";", "\"", "NULL");
    }

    private static SliceManifest manifest() {
        return new SliceManifest(
                1, "run-1", "run-1", "default", NOW, ExportMode.COMPLETE,
                HASH_A, format(), List.of(manifestArtifact("masks")));
    }
}
