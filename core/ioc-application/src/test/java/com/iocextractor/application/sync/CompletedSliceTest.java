package com.iocextractor.application.sync;

import com.iocextractor.application.export.ArtifactCoverage;
import com.iocextractor.application.export.ExportFormat;
import com.iocextractor.application.export.ExportMode;
import com.iocextractor.application.export.SliceArtifactManifest;
import com.iocextractor.application.export.SliceManifest;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CompletedSliceTest {

    private static final String HASH = "a".repeat(64);
    private static final SliceManifest MANIFEST = new SliceManifest(
            1,
            "slice-one",
            "slice-one",
            "reputation",
            Instant.parse("2026-06-28T00:00:00Z"),
            ExportMode.COMPLETE,
            HASH,
            new ExportFormat("csv", "UTF-8", ";", "\"", "NULL"),
            List.of(new SliceArtifactManifest(
                    "masks",
                    "masks.csv",
                    1,
                    ArtifactCoverage.empty(),
                    1,
                    HASH,
                    HASH,
                    HASH)));

    @Test
    void normalizesDirectoryAndKeepsManifestBinding() {
        var completed = new CompletedSlice(
                "slice-one",
                "reputation",
                "20260628T000000Z__slice-one",
                HASH,
                Path.of("var/../var/export/reputation/slice-one"),
                MANIFEST);

        assertThat(completed.directory()).isAbsolute().isNormalized();
        assertThat(completed.manifest()).isSameAs(MANIFEST);
    }

    @Test
    void rejectsMismatchedManifestIdentityAndUnsafePathSegments() {
        assertThatThrownBy(() -> new CompletedSlice(
                "other",
                "reputation",
                "slice-one",
                HASH,
                Path.of("slice-one"),
                MANIFEST))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("id");
        assertThatThrownBy(() -> new CompletedSlice(
                "slice-one",
                "../reputation",
                "slice-one",
                HASH,
                Path.of("slice-one"),
                MANIFEST))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("profile");
        assertThatThrownBy(() -> new CompletedSlice(
                "slice-one",
                "reputation",
                "slice/one",
                HASH,
                Path.of("slice-one"),
                MANIFEST))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sliceName");
    }
}
