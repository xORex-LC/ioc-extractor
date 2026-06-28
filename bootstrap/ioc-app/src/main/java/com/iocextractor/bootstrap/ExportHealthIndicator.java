package com.iocextractor.bootstrap;

import com.iocextractor.application.export.ArtifactRevision;
import com.iocextractor.application.export.ExportPlan;
import com.iocextractor.application.export.ExportProgress;
import com.iocextractor.application.export.ExportRun;
import com.iocextractor.application.export.ExportRunStatus;
import com.iocextractor.application.port.out.export.ArtifactRevisionReader;
import com.iocextractor.application.port.out.export.ExportProgressStore;
import com.iocextractor.application.port.out.export.ExportRunReader;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Actuator read model for local artifact-emission freshness and failures. */
public final class ExportHealthIndicator implements HealthIndicator {

    private final List<ExportPlan> plans;
    private final ArtifactRevisionReader revisions;
    private final ExportProgressStore progress;
    private final ExportRunReader runs;
    private final Clock clock;

    /** Creates a health contributor over already-resolved profiles and storage-neutral ports. */
    public ExportHealthIndicator(List<ExportPlan> plans,
                                 ArtifactRevisionReader revisions,
                                 ExportProgressStore progress,
                                 ExportRunReader runs,
                                 Clock clock) {
        this.plans = List.copyOf(Objects.requireNonNull(plans, "plans"));
        this.revisions = Objects.requireNonNull(revisions, "revisions");
        this.progress = Objects.requireNonNull(progress, "progress");
        this.runs = Objects.requireNonNull(runs, "runs");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public Health health() {
        boolean failedAfterCompletion = false;
        Map<String, Object> profiles = new LinkedHashMap<>();
        for (ExportPlan plan : plans) {
            ProfileHealth profile = inspect(plan);
            profiles.put(plan.profile().name(), profile.details());
            failedAfterCompletion |= profile.failedAfterCompletion();
        }
        Health.Builder builder = failedAfterCompletion ? Health.down() : Health.up();
        return builder.withDetail("profiles", profiles).build();
    }

    private ProfileHealth inspect(ExportPlan plan) {
        String profile = plan.profile().name();
        List<String> artifacts = plan.artifacts().stream()
                .map(spec -> spec.artifactName()).toList();
        Map<String, ArtifactRevision> current = revisions.read(artifacts).stream()
                .collect(Collectors.toMap(ArtifactRevision::artifactName, Function.identity()));
        Map<String, ExportProgress> exported = progress.findByProfile(profile).stream()
                .collect(Collectors.toMap(ExportProgress::artifactName, Function.identity()));
        long lag = artifacts.stream().mapToLong(artifact -> Math.max(0,
                current.getOrDefault(artifact, new ArtifactRevision(artifact, 0, null)).revision()
                        - Optional.ofNullable(exported.get(artifact))
                        .map(ExportProgress::lastRevision).orElse(0L)))
                .sum();
        Optional<ExportRun> completed = runs.findLatest(profile, ExportRunStatus.COMPLETED);
        Optional<ExportRun> failed = runs.findLatest(profile, ExportRunStatus.FAILED);
        boolean unhealthy = failed.isPresent()
                && (completed.isEmpty() || failed.get().updatedAt().isAfter(completed.get().updatedAt()));

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("revisionLag", lag);
        completed.ifPresent(run -> {
            details.put("lastCompletedAt", run.updatedAt().toString());
            details.put("lastSliceId", run.runId());
            details.put("sliceAgeSeconds", ageSeconds(run.updatedAt()));
        });
        failed.ifPresent(run -> details.put("lastFailedAt", run.updatedAt().toString()));
        return new ProfileHealth(details, unhealthy);
    }

    private long ageSeconds(Instant createdAt) {
        return Math.max(0, Duration.between(createdAt, clock.instant()).toSeconds());
    }

    private record ProfileHealth(Map<String, Object> details, boolean failedAfterCompletion) {
    }
}
