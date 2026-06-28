package com.iocextractor.application.export;

import com.iocextractor.application.artifact.ArtifactRow;
import com.iocextractor.application.port.out.export.ArtifactSliceWriter;
import com.iocextractor.application.port.out.export.ExportObserver;
import com.iocextractor.application.port.out.export.ExportRunLedger;
import com.iocextractor.application.port.out.export.SnapshotRowConsumer;
import com.iocextractor.application.port.out.export.SnapshotSliceReader;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

final class ExportFixtures {

    static final Instant NOW = Instant.parse("2026-06-28T00:00:00Z");
    static final String IDENTITY = "a".repeat(64);
    static final String SCHEMA = "b".repeat(64);
    static final String CONTENT = "c".repeat(64);
    static final String OLD_CONTENT = "d".repeat(64);
    static final String MANIFEST = "e".repeat(64);

    private ExportFixtures() {
    }

    static ExportPlan plan() {
        ExportArtifactSpec masks = new ExportArtifactSpec(
                "masks", "masks.csv", List.of("id", "mask"), 1, IDENTITY, SCHEMA, SCHEMA);
        return new ExportPlan(1,
                new ExportProfile("reputation", ExportMode.COMPLETE, List.of("masks")),
                new ExportFormat("csv", "UTF-8", ";", "\"", "NULL"),
                List.of(masks));
    }

    static ExportRun run(String id, ExportRunStatus status) {
        String manifest = status == ExportRunStatus.STAGED
                || status == ExportRunStatus.AVAILABLE
                || status == ExportRunStatus.COMPLETED ? MANIFEST : null;
        return new ExportRun(id, "reputation", status, "20260628T000000Z__" + id,
                plan().planHash(), manifest, NOW, NOW, status == ExportRunStatus.FAILED ? "failed" : null);
    }

    static SliceManifest manifest(String runId, long revision, String contentHash) {
        return new SliceManifest(1, runId, runId, "reputation", NOW, ExportMode.COMPLETE,
                plan().planHash(), plan().format(), List.of(new SliceArtifactManifest(
                "masks", "masks.csv", 1, new ArtifactCoverage(revision, NOW, revision),
                1, IDENTITY, SCHEMA, contentHash)));
    }

    static ExportProgress progress(long revision, String contentHash, String sliceId, String planHash) {
        return new ExportProgress("reputation", "masks", revision, contentHash,
                sliceId, planHash, NOW);
    }

    static final class FakeLedger implements ExportRunLedger {
        final Map<String, ExportRun> runs = new LinkedHashMap<>();
        List<ExportProgress> progress = List.of();
        boolean allowStart = true;
        int starts;

        void seed(ExportRun run) {
            runs.put(run.runId(), run);
        }

        @Override
        public Optional<ExportRun> tryStart(ExportRun startedRun) {
            starts++;
            if (!allowStart) {
                return Optional.empty();
            }
            runs.put(startedRun.runId(), startedRun);
            return Optional.of(startedRun);
        }

        @Override
        public ExportRun transition(String runId,
                                    ExportRunStatus expected,
                                    ExportRunStatus next,
                                    String manifestSha256,
                                    String reason) {
            ExportRun actual = runs.get(runId);
            if (actual.status() != expected) {
                throw new IllegalStateException("expected " + expected + " but was " + actual.status());
            }
            String hash = manifestSha256 != null ? manifestSha256 : actual.manifestSha256();
            ExportRun updated = new ExportRun(actual.runId(), actual.profile(), next, actual.sliceName(),
                    actual.planHash(), hash, actual.startedAt(), NOW,
                    next == ExportRunStatus.FAILED ? reason : null);
            runs.put(runId, updated);
            return updated;
        }

        @Override
        public ExportRun finish(String runId,
                                ExportRunStatus expected,
                                ExportRunStatus terminal,
                                List<ExportProgress> progress) {
            this.progress = List.copyOf(progress);
            return transition(runId, expected, terminal, null, null);
        }

        @Override
        public Optional<ExportRun> find(String runId) {
            return Optional.ofNullable(runs.get(runId));
        }

        @Override
        public List<ExportRun> findIncomplete() {
            return runs.values().stream().filter(run -> !run.status().terminal()).toList();
        }
    }

    static final class FakeWriter implements ArtifactSliceWriter {
        SliceInspectionState state = SliceInspectionState.MISSING;
        long revision = 1;
        String contentHash = CONTENT;
        int stages;
        int recoveries;
        int publications;
        int discards;

        @Override
        public StagedSlice stage(ExportRun run,
                                 SnapshotRequest request,
                                 SnapshotSliceReader reader) {
            stages++;
            reader.stream(request, new SnapshotRowConsumer() {
                @Override public void begin(SnapshotMetadata metadata) { }
                @Override public void beginArtifact(SnapshotArtifactMetadata artifact) { }
                @Override public void row(ArtifactRow row) { }
                @Override public void endArtifact() { }
                @Override public void end() { }
            });
            state = SliceInspectionState.STAGED;
            SliceManifest manifest = manifest(run.runId(), revision, contentHash);
            return new StagedSlice(run.runId(), run.sliceName(), MANIFEST, manifest);
        }

        @Override
        public SliceInspection inspect(ExportRun run) {
            boolean hasManifest = state == SliceInspectionState.RECOVERABLE
                    || state == SliceInspectionState.STAGED
                    || state == SliceInspectionState.AVAILABLE;
            return new SliceInspection(run.runId(), state,
                    hasManifest ? MANIFEST : null,
                    hasManifest ? manifest(run.runId(), revision, contentHash) : null,
                    state == SliceInspectionState.CORRUPT || state == SliceInspectionState.CONFLICT
                            ? "corrupt test slice" : null);
        }

        @Override
        public StagedSlice recoverStaging(ExportRun run) {
            recoveries++;
            state = SliceInspectionState.STAGED;
            return new StagedSlice(run.runId(), run.sliceName(), MANIFEST,
                    manifest(run.runId(), revision, contentHash));
        }

        @Override
        public AvailableSlice makeAvailable(ExportRun run) {
            publications++;
            state = SliceInspectionState.AVAILABLE;
            return new AvailableSlice(run.runId(), run.sliceName(), MANIFEST,
                    manifest(run.runId(), revision, contentHash));
        }

        @Override
        public void discardStaging(ExportRun run) {
            discards++;
            state = SliceInspectionState.MISSING;
        }
    }

    static final class CountingSnapshotReader implements SnapshotSliceReader {
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public SnapshotMetadata stream(SnapshotRequest request, SnapshotRowConsumer consumer) {
            calls.incrementAndGet();
            SnapshotArtifactMetadata artifact = new SnapshotArtifactMetadata(
                    "masks", "masks.csv", List.of("id", "mask"),
                    new ArtifactCoverage(1, NOW, 1), 1, IDENTITY, SCHEMA);
            SnapshotMetadata metadata = new SnapshotMetadata(
                    "reputation", request.plan().planHash(), NOW, List.of(artifact));
            consumer.begin(metadata);
            consumer.beginArtifact(artifact);
            consumer.row(ArtifactRow.ordered(Map.of("id", "1", "mask", "example.org")));
            consumer.endArtifact();
            consumer.end();
            return metadata;
        }
    }

    static final class RecordingObserver implements ExportObserver {
        final List<String> events = new ArrayList<>();

        @Override
        public void started(ExportRun run) {
            events.add("started:" + run.status());
        }

        @Override
        public void sliceWritten(ExportRun run, StagedSlice slice) {
            events.add("written:" + slice.sliceId());
        }

        @Override
        public void completed(ExportRun run) {
            events.add("completed:" + run.status());
        }

        @Override
        public void recovering(ExportRun run) {
            events.add("recovering:" + run.status());
        }
    }
}
