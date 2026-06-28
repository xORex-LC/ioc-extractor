package com.iocextractor.bootstrap;

import com.iocextractor.application.export.ArtifactRevision;
import com.iocextractor.application.export.ExportArtifactSpec;
import com.iocextractor.application.export.ExportFormat;
import com.iocextractor.application.export.ExportMode;
import com.iocextractor.application.export.ExportPlan;
import com.iocextractor.application.export.ExportProfile;
import com.iocextractor.application.export.ExportProgress;
import com.iocextractor.application.export.ExportRun;
import com.iocextractor.application.export.ExportRunStatus;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ExportHealthIndicatorTest {

    private static final Instant NOW = Instant.parse("2026-06-28T01:00:00Z");

    @Test
    void reportsCompletionAgeAndRevisionLagPerProfile() {
        ExportRun completed = run(ExportRunStatus.COMPLETED, NOW.minusSeconds(120));
        var indicator = new ExportHealthIndicator(
                List.of(plan()),
                artifacts -> List.of(new ArtifactRevision("masks", 5, NOW.minusSeconds(10))),
                profile -> List.of(new ExportProgress(
                        profile, "masks", 3, hash('c'), "old-slice", hash('d'), NOW.minusSeconds(120))),
                (profile, status) -> status == ExportRunStatus.COMPLETED
                        ? Optional.of(completed) : Optional.empty(),
                Clock.fixed(NOW, ZoneOffset.UTC));

        var health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> profiles =
                (Map<String, Map<String, Object>>) health.getDetails().get("profiles");
        assertThat(profiles.get("reputation"))
                .containsEntry("revisionLag", 2L)
                .containsEntry("sliceAgeSeconds", 120L)
                .containsEntry("lastSliceId", "run-COMPLETED");
    }

    @Test
    void newerFailureMarksExportHealthDown() {
        ExportRun completed = run(ExportRunStatus.COMPLETED, NOW.minusSeconds(120));
        ExportRun failed = run(ExportRunStatus.FAILED, NOW.minusSeconds(30));
        var indicator = new ExportHealthIndicator(
                List.of(plan()),
                artifacts -> List.of(new ArtifactRevision("masks", 0, null)),
                profile -> List.of(),
                (profile, status) -> status == ExportRunStatus.COMPLETED
                        ? Optional.of(completed) : Optional.of(failed),
                Clock.fixed(NOW, ZoneOffset.UTC));

        assertThat(indicator.health().getStatus()).isEqualTo(Status.DOWN);
    }

    private ExportPlan plan() {
        return new ExportPlan(1,
                new ExportProfile("reputation", ExportMode.COMPLETE, List.of("masks")),
                new ExportFormat("csv", "UTF-8", ";", "\"", "NULL"),
                List.of(new ExportArtifactSpec(
                        "masks", "masks.csv", List.of("id"), 1,
                        hash('a'), hash('b'), hash('c'))));
    }

    private ExportRun run(ExportRunStatus status, Instant at) {
        return new ExportRun(
                "run-" + status, "reputation", status, "slice-" + status,
                hash('a'), status == ExportRunStatus.COMPLETED ? hash('b') : null,
                at.minusSeconds(10), at, status == ExportRunStatus.FAILED ? "failed" : null);
    }

    private String hash(char value) {
        return String.valueOf(value).repeat(64);
    }
}
