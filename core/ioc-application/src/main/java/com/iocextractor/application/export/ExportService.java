package com.iocextractor.application.export;

import com.iocextractor.application.port.in.export.ExportArtifactsCommand;
import com.iocextractor.application.port.in.export.ExportArtifactsResult;
import com.iocextractor.application.port.in.export.ExportArtifactsUseCase;
import com.iocextractor.application.port.out.export.ArtifactRevisionReader;
import com.iocextractor.application.port.out.export.ArtifactSliceWriter;
import com.iocextractor.application.port.out.export.ExportObserver;
import com.iocextractor.application.port.out.export.ExportProgressStore;
import com.iocextractor.application.port.out.export.ExportRunLedger;
import com.iocextractor.application.port.out.export.SnapshotSliceReader;

import java.time.Clock;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Application orchestrator for one complete immutable artifact export run.
 *
 * <p>The service coordinates ports and durable checkpoints. It never reads rows, formats bytes,
 * manages database transactions or manipulates paths itself.
 */
public final class ExportService implements ExportArtifactsUseCase {

    private static final DateTimeFormatter SLICE_TIME = DateTimeFormatter
            .ofPattern("yyyyMMdd'T'HHmmss'Z'")
            .withZone(ZoneOffset.UTC);

    private final Map<String, ExportPlan> plans;
    private final ArtifactRevisionReader revisionReader;
    private final ExportProgressStore progressStore;
    private final ExportRunLedger ledger;
    private final SnapshotSliceReader snapshotReader;
    private final ArtifactSliceWriter sliceWriter;
    private final ExportChangeDetector changeDetector;
    private final ExportObserver observer;
    private final Clock clock;
    private final Supplier<String> runIds;

    /** Creates an export orchestrator with random run ids and no operational event delivery. */
    public ExportService(Collection<ExportPlan> plans,
                         ArtifactRevisionReader revisionReader,
                         ExportProgressStore progressStore,
                         ExportRunLedger ledger,
                         SnapshotSliceReader snapshotReader,
                         ArtifactSliceWriter sliceWriter,
                         Clock clock) {
        this(plans, revisionReader, progressStore, ledger, snapshotReader, sliceWriter,
                new ExportChangeDetector(), NoopExportObserver.INSTANCE, clock,
                () -> UUID.randomUUID().toString());
    }

    /** Creates an export orchestrator with explicit policy, event boundary and run-id source. */
    public ExportService(Collection<ExportPlan> plans,
                         ArtifactRevisionReader revisionReader,
                         ExportProgressStore progressStore,
                         ExportRunLedger ledger,
                         SnapshotSliceReader snapshotReader,
                         ArtifactSliceWriter sliceWriter,
                         ExportChangeDetector changeDetector,
                         ExportObserver observer,
                         Clock clock,
                         Supplier<String> runIds) {
        this.plans = index(plans);
        this.revisionReader = Objects.requireNonNull(revisionReader, "revisionReader");
        this.progressStore = Objects.requireNonNull(progressStore, "progressStore");
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        this.snapshotReader = Objects.requireNonNull(snapshotReader, "snapshotReader");
        this.sliceWriter = Objects.requireNonNull(sliceWriter, "sliceWriter");
        this.changeDetector = Objects.requireNonNull(changeDetector, "changeDetector");
        this.observer = Objects.requireNonNull(observer, "observer");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.runIds = Objects.requireNonNull(runIds, "runIds");
    }

    @Override
    public ExportArtifactsResult export(ExportArtifactsCommand command) {
        Objects.requireNonNull(command, "command");
        ExportPlan plan = requirePlan(command.profile());
        if (plan.profile().mode() != ExportMode.COMPLETE) {
            throw new IllegalArgumentException("Export mode is not supported in v1: " + plan.profile().mode());
        }
        List<String> artifacts = plan.artifacts().stream().map(ExportArtifactSpec::artifactName).toList();
        List<ArtifactRevision> revisions = revisionReader.read(artifacts);
        List<ExportProgress> progress = progressStore.findByProfile(plan.profile().name());
        if (!changeDetector.requiresMaterialization(plan, revisions, progress)) {
            return ExportArtifactsResult.unchanged(plan.profile().name());
        }

        ExportRun candidate = newRun(plan);
        ExportRun started = ledger.tryStart(candidate)
                .orElseThrow(() -> new IllegalStateException("Another export run is already active"));
        observer.started(started);

        StagedSlice staged = sliceWriter.stage(started, new SnapshotRequest(plan), snapshotReader);
        observer.sliceWritten(started, staged);
        if (changeDetector.sameContent(staged.manifest(), progress)) {
            sliceWriter.discardStaging(started);
            List<ExportProgress> skipped = changeDetector.skippedProgress(
                    staged.manifest(), progress, clock.instant());
            ExportRun terminal = ledger.finish(started.runId(), ExportRunStatus.STARTED,
                    ExportRunStatus.SKIPPED, skipped);
            observer.completed(terminal);
            return result(terminal);
        }

        ExportRun stagedRun = ledger.transition(started.runId(), ExportRunStatus.STARTED,
                ExportRunStatus.STAGED, staged.manifestSha256(), null);
        sliceWriter.makeAvailable(stagedRun);
        ExportRun available = ledger.transition(started.runId(), ExportRunStatus.STAGED,
                ExportRunStatus.AVAILABLE, null, null);
        List<ExportProgress> completed = changeDetector.completedProgress(
                staged.manifest(), clock.instant());
        ExportRun terminal = ledger.finish(started.runId(), ExportRunStatus.AVAILABLE,
                ExportRunStatus.COMPLETED, completed);
        observer.completed(terminal);
        return result(terminal);
    }

    private ExportRun newRun(ExportPlan plan) {
        var now = clock.instant();
        String runId = Objects.requireNonNull(runIds.get(), "generated runId");
        String sliceName = SLICE_TIME.format(now) + "__" + runId;
        return ExportRun.started(runId, plan.profile().name(), sliceName, plan.planHash(), now);
    }

    private ExportPlan requirePlan(String profile) {
        ExportPlan plan = plans.get(profile);
        if (plan == null) {
            throw new IllegalArgumentException("Unknown export profile: " + profile);
        }
        return plan;
    }

    private ExportArtifactsResult result(ExportRun run) {
        return new ExportArtifactsResult(run.runId(), run.profile(), run.status(), run.sliceName());
    }

    private Map<String, ExportPlan> index(Collection<ExportPlan> source) {
        Map<String, ExportPlan> result = new LinkedHashMap<>();
        for (ExportPlan plan : List.copyOf(Objects.requireNonNull(source, "plans"))) {
            if (result.put(plan.profile().name(), plan) != null) {
                throw new IllegalArgumentException("Duplicate export profile: " + plan.profile().name());
            }
        }
        if (result.isEmpty()) {
            throw new IllegalArgumentException("At least one export plan is required");
        }
        return Map.copyOf(result);
    }
}
