package com.iocextractor.application.export;

import org.junit.jupiter.api.Test;

import java.util.List;

import static com.iocextractor.application.export.ExportFixtures.CONTENT;
import static com.iocextractor.application.export.ExportFixtures.NOW;
import static com.iocextractor.application.export.ExportFixtures.OLD_CONTENT;
import static org.assertj.core.api.Assertions.assertThat;

class ExportChangeDetectorTest {

    private final ExportChangeDetector detector = new ExportChangeDetector();
    private final ExportPlan plan = ExportFixtures.plan();

    @Test
    void preGateRequiresExactOrderedRevisionsAndPlanIdentity() {
        ArtifactRevision current = new ArtifactRevision("masks", 4, NOW);
        ExportProgress same = ExportFixtures.progress(4, CONTENT, "slice-old", plan.planHash());

        assertThat(detector.requiresMaterialization(plan, List.of(current), List.of(same))).isFalse();
        assertThat(detector.requiresMaterialization(plan,
                List.of(new ArtifactRevision("masks", 5, NOW)), List.of(same))).isTrue();
        assertThat(detector.requiresMaterialization(plan, List.of(current),
                List.of(ExportFixtures.progress(4, CONTENT, "slice-old", "f".repeat(64))))).isTrue();
        assertThat(detector.requiresMaterialization(plan, List.of(current), List.of())).isTrue();
    }

    @Test
    void candidateIsRedundantOnlyWhenContentPlanAndCoveredRevisionAreUnchanged() {
        SliceManifest unchanged = ExportFixtures.manifest("run-unchanged", 4, CONTENT);
        SliceManifest newLifecycle = ExportFixtures.manifest("run-new", 9, CONTENT);
        ExportProgress previous = ExportFixtures.progress(4, CONTENT, "slice-old", plan.planHash());

        assertThat(detector.isRedundant(unchanged, List.of(previous))).isTrue();
        assertThat(detector.isRedundant(newLifecycle, List.of(previous))).isFalse();
        assertThat(detector.isRedundant(unchanged, List.of(
                ExportFixtures.progress(4, OLD_CONTENT, "slice-old", plan.planHash())))).isFalse();
        assertThat(detector.isRedundant(unchanged, List.of(
                ExportFixtures.progress(4, CONTENT, "slice-old", "f".repeat(64))))).isFalse();
        assertThat(detector.skippedProgress(unchanged, List.of(previous), NOW))
                .singleElement()
                .satisfies(progress -> {
                    assertThat(progress.lastRevision()).isEqualTo(4);
                    assertThat(progress.lastSha256()).isEqualTo(CONTENT);
                    assertThat(progress.lastSliceId()).isEqualTo("slice-old");
                });
    }
}
