package com.iocextractor.bootstrap;

import com.iocextractor.application.export.ArtifactCoverage;
import com.iocextractor.application.export.ExportFormat;
import com.iocextractor.application.export.ExportMode;
import com.iocextractor.application.export.SliceArtifactManifest;
import com.iocextractor.application.export.SliceManifest;
import com.iocextractor.application.port.in.sync.ArtifactPublishResult;
import com.iocextractor.application.port.in.sync.RemoteFetchResult;
import com.iocextractor.application.port.out.sync.CompletedSliceCatalog;
import com.iocextractor.application.port.out.sync.PublishLedger;
import com.iocextractor.application.sync.PublishLedgerStatusCounts;
import com.iocextractor.application.sync.CompletedSlice;
import com.iocextractor.application.sync.PublishRecord;
import com.iocextractor.application.sync.PublishStatus;
import com.iocextractor.application.sync.PublishTarget;
import com.iocextractor.application.sync.RemoteFetchSource;
import com.iocextractor.platform.concurrent.KeyedSerialExecutorSnapshot;
import com.iocextractor.platform.concurrent.KeyedWorkSnapshot;
import com.iocextractor.platform.concurrent.WorkAdmission;
import com.iocextractor.platform.concurrent.WorkKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.actuate.health.Status;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class SyncHealthIndicatorTest {

    private static final Instant NOW = Instant.parse("2026-06-28T12:00:00Z");
    private static final String HASH = "a".repeat(64);

    @TempDir
    Path tempDir;

    @Test
    void reportsLatestRunsDurableBacklogEndpointStateAndPinnedSlices() {
        SyncHealthState state = new SyncHealthState(Clock.fixed(NOW, ZoneOffset.UTC));
        state.recordFetch("incoming", "primary", new RemoteFetchResult(2, 1, 0));
        state.recordPublish("delivery", "primary", "reputation",
                new ArtifactPublishResult(0, 0, 1, 0));
        List<PublishRecord> records = List.of(
                record("pending", PublishStatus.PENDING),
                record("failed", PublishStatus.FAILED));
        CompletedSlice pinned = slice("pinned");
        CompletedSlice released = slice("released");
        SyncHealthIndicator indicator = new SyncHealthIndicator(
                List.of(new RemoteFetchSource(
                        "incoming", "primary", "/in", List.of("*"), List.of())),
                List.of(new PublishTarget("delivery", "primary", "/out", "reputation")),
                state,
                ledger(records),
                catalog(List.of(pinned, released)),
                descriptor -> descriptor.sliceId().equals("released"));

        var health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails())
                .containsEntry("publishPending", 1L)
                .containsEntry("publishFailed", 1L)
                .containsEntry("retentionPinnedSlices", 1L);
        @SuppressWarnings("unchecked")
        Map<String, Object> endpoints = (Map<String, Object>) health.getDetails().get("endpoints");
        assertThat(endpoints).containsEntry("primary", "DOWN");
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> fetchSources =
                (Map<String, Map<String, Object>>) health.getDetails().get("fetchSources");
        assertThat(fetchSources.get("incoming"))
                .containsEntry("lastCompletedAt", NOW.toString())
                .containsEntry("fetched", 2);
    }

    @Test
    void neverRunOperationsAreVisibleWithoutMakingHealthDown() {
        SyncHealthIndicator indicator = new SyncHealthIndicator(
                List.of(new RemoteFetchSource(
                        "incoming", "primary", "/in", List.of("*"), List.of())),
                List.of(new PublishTarget("delivery", "primary", "/out", "reputation")),
                new SyncHealthState(Clock.fixed(NOW, ZoneOffset.UTC)),
                ledger(List.of()),
                catalog(List.of()),
                descriptor -> false);

        var health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> targets =
                (Map<String, Map<String, Object>>) health.getDetails().get("publishTargets");
        assertThat(targets.get("delivery")).containsEntry("status", "NEVER_RUN");
    }

    @Test
    void reportsRecoverableKeyedShedWithoutMakingHealthDown() {
        SyncHealthState state = new SyncHealthState(Clock.fixed(NOW, ZoneOffset.UTC));
        WorkKey key = WorkKey.of("primary");
        state.recordKeyedRejection(WorkAdmission.rejected(key, 64));
        SyncHealthIndicator indicator = new SyncHealthIndicator(
                List.of(new RemoteFetchSource(
                        "incoming", "primary", "/in", List.of("*"), List.of())),
                List.of(new PublishTarget("delivery", "primary", "/out", "reputation")),
                state,
                ledger(List.of()),
                catalog(List.of()),
                () -> new KeyedSerialExecutorSnapshot(List.of(
                        new KeyedWorkSnapshot(key, 3, true, Duration.ofSeconds(5)))),
                descriptor -> false);

        var health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        @SuppressWarnings("unchecked")
        Map<String, Object> keyedExecutor =
                (Map<String, Object>) health.getDetails().get("keyedExecutor");
        assertThat(keyedExecutor.get("runningKeys")).isEqualTo(List.of("primary"));
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> keys =
                (Map<String, Map<String, Object>>) keyedExecutor.get("keys");
        assertThat(keys.get("primary"))
                .containsEntry("running", true)
                .containsEntry("queueDepth", 3)
                .containsEntry("oldestAgeMs", 5000L)
                .containsEntry("shedToReconcile", true)
                .containsEntry("rejectedQueuedDepth", 64);
    }

    @Test
    void clearsKeyedDegradationSignalAfterSuccessfulWork() {
        SyncHealthState state = new SyncHealthState(Clock.fixed(NOW, ZoneOffset.UTC));
        WorkKey key = WorkKey.of("primary");
        state.recordKeyedRejection(WorkAdmission.rejected(key, 64));

        boolean recovered = state.recordKeyedSuccess(key);

        assertThat(recovered).isTrue();
        assertThat(state.keyedExecutorSignals()).isEmpty();
    }

    private PublishLedger ledger(List<PublishRecord> records) {
        return new PublishLedger() {
            @Override
            public PublishRecord ensurePending(PublishRecord pending) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Optional<PublishRecord> find(String sliceId, String targetId) {
                return records.stream()
                        .filter(record -> record.sliceId().equals(sliceId)
                                && record.targetId().equals(targetId))
                        .findFirst();
            }

            @Override
            public List<PublishRecord> findBySlice(String sliceId) {
                return records.stream().filter(record -> record.sliceId().equals(sliceId)).toList();
            }

            @Override
            public List<PublishRecord> findRetryable() {
                return records.stream()
                        .filter(record -> record.status() == PublishStatus.PENDING
                                || record.status() == PublishStatus.FAILED)
                        .toList();
            }

            @Override
            public List<PublishRecord> findRetryable(Instant staleInProgressBefore) {
                return findRetryable();
            }

            @Override
            public PublishLedgerStatusCounts countByStatus(Optional<String> profile,
                                                           Optional<String> targetId,
                                                           Optional<String> endpoint) {
                throw new UnsupportedOperationException();
            }

            @Override
            public List<PublishRecord> findAll() {
                return records;
            }

            @Override
            public PublishRecord transition(String sliceId,
                                            String targetId,
                                            PublishStatus expected,
                                            PublishStatus next,
                                            String lastError,
                                            String remoteVerification) {
                throw new UnsupportedOperationException();
            }
        };
    }

    private CompletedSliceCatalog catalog(List<CompletedSlice> slices) {
        return new CompletedSliceCatalog() {
            @Override
            public List<CompletedSlice> listCompleted(String profile) {
                return slices.stream()
                        .filter(slice -> slice.profile().equals(profile))
                        .toList();
            }

            @Override
            public Optional<CompletedSlice> find(String profile, String sliceName) {
                return slices.stream()
                        .filter(slice -> slice.profile().equals(profile))
                        .filter(slice -> slice.sliceName().equals(sliceName))
                        .findFirst();
            }
        };
    }

    private PublishRecord record(String slice, PublishStatus status) {
        return new PublishRecord(
                slice, "delivery", "reputation", slice, HASH,
                "primary", "/out/" + slice, status, 0,
                status == PublishStatus.FAILED ? "failed" : null,
                null, NOW.minusSeconds(60), NOW);
    }

    private CompletedSlice slice(String id) {
        SliceManifest manifest = new SliceManifest(
                1, id, id, "reputation", NOW.minusSeconds(60), ExportMode.COMPLETE, HASH,
                new ExportFormat("csv", "UTF-8", ";", "\"", "NULL"),
                List.of(new SliceArtifactManifest(
                        "masks", "masks.csv", 1, ArtifactCoverage.empty(),
                        1, HASH, HASH, HASH)));
        return new CompletedSlice(
                id, "reputation", id, HASH, tempDir.resolve(id), manifest);
    }
}
