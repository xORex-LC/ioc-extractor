package com.iocextractor.bootstrap;

import com.iocextractor.application.export.ArtifactCoverage;
import com.iocextractor.application.export.ExportFormat;
import com.iocextractor.application.export.ExportMode;
import com.iocextractor.application.export.SliceArtifactManifest;
import com.iocextractor.application.export.SliceManifest;
import com.iocextractor.application.port.in.sync.ArtifactPublishExecutionResult;
import com.iocextractor.application.port.in.sync.RemoteFetchResult;
import com.iocextractor.application.port.out.sync.CompletedSliceCatalog;
import com.iocextractor.application.port.out.sync.PublishLedger;
import com.iocextractor.application.sync.PublishLedgerStatusCounts;
import com.iocextractor.application.sync.PublishLedgerHealthSummary;
import com.iocextractor.application.sync.CompletedSlice;
import com.iocextractor.application.sync.PublishRecord;
import com.iocextractor.application.sync.PublishStatus;
import com.iocextractor.application.sync.PublishTarget;
import com.iocextractor.application.sync.RemoteFetchSource;
import com.iocextractor.application.sync.RemoteErrorKind;
import com.iocextractor.application.sync.RemoteTransportException;
import com.iocextractor.platform.concurrent.KeyedSerialExecutorSnapshot;
import com.iocextractor.platform.concurrent.KeyedWorkSnapshot;
import com.iocextractor.platform.concurrent.WorkAdmission;
import com.iocextractor.platform.concurrent.WorkKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.health.contributor.Status;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.LinkedHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

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
                new ArtifactPublishExecutionResult(1, 0, 0, 1));
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

    @Test
    void transientTransportFailureIsDegradedAndRecoversAfterConfirmedSuccess() {
        SyncHealthState state = new SyncHealthState(Clock.fixed(NOW, ZoneOffset.UTC));
        SyncHealthIndicator indicator = new SyncHealthIndicator(
                List.of(new RemoteFetchSource(
                        "incoming", "primary", "/in", List.of("*"), List.of())),
                List.of(), state, ledger(List.of()), catalog(List.of()), descriptor -> false);
        state.recordFetchFailure("incoming", "primary",
                new RemoteTransportException(RemoteErrorKind.TRANSIENT, "connection reset"));

        assertThat(indicator.health().getStatus()).isEqualTo(new Status("DEGRADED"));

        state.recordFetch("incoming", "primary", new RemoteFetchResult(0, 0, 0));

        assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
    }

    @Test
    void reportsFreshRemoteChangeWatchReconnectWithoutDegradingHealth() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        SyncHealthState state = new SyncHealthState(clock);
        SyncHealthIndicator indicator = new SyncHealthIndicator(
                List.of(new RemoteFetchSource(
                        "incoming", "primary", "/in", List.of("*"), List.of())),
                List.of(), state, ledger(List.of()), catalog(List.of()),
                KeyedSerialExecutorSnapshot::empty, descriptor -> false, clock);
        state.recordFetchDetection("incoming", "primary", "PUSH", 3, Duration.ofMillis(12));
        state.recordRemoteChangeWatchFailure("incoming", "primary",
                new IllegalStateException("watch disconnected"));

        var health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> detection =
                (Map<String, Map<String, Object>>) health.getDetails().get("fetchDetection");
        assertThat(detection.get("incoming"))
                .containsEntry("reason", "PUSH")
                .containsEntry("detectedObjects", 3)
                .containsEntry("detectDurationMs", 12L)
                .containsEntry("status", "UP");
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> watch =
                (Map<String, Map<String, Object>>) health.getDetails().get("remoteChangeWatch");
        assertThat(watch.get("incoming"))
                .containsEntry("status", "RECONNECTING")
                .containsEntry("reconnects", 1L)
                .containsEntry("reconnectingSince", NOW.toString())
                .containsEntry("reconnectingForMs", 0L)
                .containsEntry("degradedAfterMs", 60_000L)
                .containsEntry("error", "watch disconnected");
    }

    @Test
    void degradesHealthWhenRepeatedRemoteChangeWatchFailuresExceedOutageGrace() {
        MutableClock clock = new MutableClock(NOW);
        SyncHealthState state = new SyncHealthState(clock);
        state.recordRemoteChangeWatchFailure("incoming", "primary",
                new IllegalStateException("connection refused"));
        clock.advance(Duration.ofSeconds(30));
        state.recordRemoteChangeWatchFailure("incoming", "primary",
                new IllegalStateException("connection refused"));
        clock.advance(Duration.ofSeconds(25));
        state.recordRemoteChangeWatchFailure("incoming", "primary",
                new IllegalStateException("connection refused"));
        clock.advance(Duration.ofSeconds(6));
        SyncHealthIndicator indicator = new SyncHealthIndicator(
                List.of(new RemoteFetchSource(
                        "incoming", "primary", "/in", List.of("*"), List.of())),
                List.of(), state, ledger(List.of()), catalog(List.of()),
                KeyedSerialExecutorSnapshot::empty, descriptor -> false, clock);

        var health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(new Status("DEGRADED"));
        @SuppressWarnings("unchecked")
        Map<String, Object> endpoints = (Map<String, Object>) health.getDetails().get("endpoints");
        assertThat(endpoints).containsEntry("primary", "DEGRADED");
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> watch =
                (Map<String, Map<String, Object>>) health.getDetails().get("remoteChangeWatch");
        assertThat(watch.get("incoming"))
                .containsEntry("reconnects", 3L)
                .containsEntry("reconnectingSince", NOW.toString())
                .containsEntry("reconnectingForMs", 61_000L);
    }

    @Test
    void statePreservesCoalescedDetectionAndWatchRecoveryHistory() {
        SyncHealthState state = new SyncHealthState(Clock.fixed(NOW, ZoneOffset.UTC));

        state.recordFetchDetectionCoalesced("incoming", "primary");
        state.recordFetchDetection("incoming", "primary", "PERIODIC", -1, Duration.ofMillis(7));
        state.recordFetchDetectionFailure(
                "secondary", "backup", "STARTUP",
                new RemoteTransportException(RemoteErrorKind.TRANSIENT, "temporarily unavailable"),
                Duration.ofMillis(9));
        state.recordRemoteChangeWatchSignal("incoming", "primary");
        state.recordRemoteChangeWatchEstablished("incoming", "primary");
        state.recordRemoteChangeWatchSignal("incoming", "primary");
        state.recordRemoteChangeWatchFailure("incoming", "primary", new IllegalStateException());
        state.recordRemoteChangeWatchEstablished("incoming", "primary");
        state.recordRemoteChangeWatchDisabled("secondary", "backup");

        assertThat(state.fetchDetectionSnapshots().get("incoming"))
                .satisfies(snapshot -> {
                    assertThat(snapshot.reason()).isEqualTo("PERIODIC");
                    assertThat(snapshot.detectedObjects()).isZero();
                    assertThat(snapshot.coalescedSignals()).isEqualTo(1);
                    assertThat(snapshot.duration()).isEqualTo(Duration.ofMillis(7));
                });
        assertThat(state.fetchDetectionSnapshots().get("secondary"))
                .satisfies(snapshot -> {
                    assertThat(snapshot.status()).isEqualTo(SyncOperationalStatus.DEGRADED);
                    assertThat(snapshot.error()).isEqualTo("temporarily unavailable");
                });
        assertThat(state.remoteChangeWatchSnapshots().get("incoming"))
                .satisfies(snapshot -> {
                    assertThat(snapshot.status()).isEqualTo(RemoteChangeWatchStatus.ACTIVE);
                    assertThat(snapshot.signals()).isEqualTo(2);
                    assertThat(snapshot.reconnects()).isEqualTo(1);
                    assertThat(snapshot.reArms()).isEqualTo(1);
                    assertThat(snapshot.error()).isNull();
                });
        assertThat(state.remoteChangeWatchSnapshots().get("secondary").status())
                .isEqualTo(RemoteChangeWatchStatus.DISABLED);
        assertThatIllegalArgumentException()
                .isThrownBy(() -> state.recordFetchDetection(
                        "invalid", "primary", "PERIODIC", 0, Duration.ofNanos(-1)))
                .withMessage("duration must not be negative");
    }

    @Test
    void keyedFailuresMergeAdmissionAndDispatchEvidenceUntilSuccess() {
        SyncHealthState state = new SyncHealthState(Clock.fixed(NOW, ZoneOffset.UTC));
        WorkKey first = WorkKey.of("first");
        WorkKey second = WorkKey.of("second");

        state.recordKeyedFailure(first, new IllegalStateException());
        state.recordKeyedRejection(WorkAdmission.rejected(first, 4));
        state.recordKeyedDispatchRejected(first, 2, new IllegalArgumentException());
        state.recordKeyedDispatchRejected(second, 3, new IllegalStateException("executor stopped"));
        state.recordKeyedFailure(second, new IllegalStateException("work failed"));

        assertThat(state.keyedExecutorSignals()).containsOnlyKeys("first", "second");
        assertThat(state.keyedExecutorSignals().get("first"))
                .satisfies(signal -> {
                    assertThat(signal.shedToReconcile()).isTrue();
                    assertThat(signal.rejectedQueuedDepth()).isEqualTo(4);
                    assertThat(signal.abandonedWork()).isEqualTo(2);
                    assertThat(signal.lastDispatchFailure()).isEqualTo("IllegalArgumentException");
                    assertThat(signal.error()).isEqualTo("IllegalStateException");
                });
        assertThat(state.keyedExecutorSignals().get("second"))
                .satisfies(signal -> {
                    assertThat(signal.lastDispatchFailure()).isEqualTo("executor stopped");
                    assertThat(signal.error()).isEqualTo("work failed");
                });
        assertThat(state.recordKeyedSuccess(first)).isTrue();
        assertThat(state.recordKeyedSuccess(first)).isFalse();
    }

    @Test
    void healthReportsDetectionPublishAndExecutorFailuresPerEndpoint() {
        SyncHealthState state = new SyncHealthState(Clock.fixed(NOW, ZoneOffset.UTC));
        state.recordFetchDetectionFailure(
                "incoming", "primary", "PERIODIC",
                new RemoteTransportException(RemoteErrorKind.AUTH_FAILED, "access denied"),
                Duration.ofMillis(3));
        state.recordPublishFailure(
                "delivery", "backup", "reputation",
                new RemoteTransportException(RemoteErrorKind.TRANSIENT, "network busy"));
        state.recordKeyedFailure(WorkKey.of("worker"), new IllegalStateException("worker failed"));
        SyncHealthIndicator indicator = new SyncHealthIndicator(
                List.of(new RemoteFetchSource(
                        "incoming", "primary", "/in", List.of("*"), List.of())),
                List.of(new PublishTarget("delivery", "backup", "/out", "reputation")),
                state, ledger(List.of()), catalog(List.of()),
                KeyedSerialExecutorSnapshot::empty, descriptor -> false,
                Clock.fixed(NOW, ZoneOffset.UTC));

        var health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        @SuppressWarnings("unchecked")
        Map<String, Object> endpoints = (Map<String, Object>) health.getDetails().get("endpoints");
        assertThat(endpoints).containsEntry("primary", "DOWN").containsEntry("backup", "DEGRADED");
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> detections =
                (Map<String, Map<String, Object>>) health.getDetails().get("fetchDetection");
        assertThat(detections.get("incoming"))
                .containsEntry("status", "DOWN")
                .containsEntry("error", "access denied");
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> publishes =
                (Map<String, Map<String, Object>>) health.getDetails().get("publishTargets");
        assertThat(publishes.get("delivery"))
                .containsEntry("status", "DEGRADED")
                .containsEntry("error", "network busy");
    }

    @Test
    void healthClampsNegativeExecutorAgeAndShowsSignalOnlyKeys() {
        SyncHealthState state = new SyncHealthState(Clock.fixed(NOW, ZoneOffset.UTC));
        state.recordKeyedRejection(WorkAdmission.rejected(WorkKey.of("signal-only"), 2));
        WorkKey live = WorkKey.of("live-only");
        SyncHealthIndicator indicator = new SyncHealthIndicator(
                List.of(), List.of(), state, ledger(List.of()), catalog(List.of()),
                () -> new KeyedSerialExecutorSnapshot(List.of(
                        new KeyedWorkSnapshot(live, 0, false, Duration.ofMillis(-1)))),
                descriptor -> false, Clock.fixed(NOW, ZoneOffset.UTC));

        var health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        @SuppressWarnings("unchecked")
        Map<String, Object> keyedExecutor =
                (Map<String, Object>) health.getDetails().get("keyedExecutor");
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> keys =
                (Map<String, Map<String, Object>>) keyedExecutor.get("keys");
        assertThat(keys.get("live-only"))
                .containsEntry("running", false)
                .containsEntry("queueDepth", 0)
                .containsEntry("oldestAgeMs", 0L)
                .containsEntry("shedToReconcile", false);
        assertThat(keys.get("signal-only"))
                .containsEntry("running", false)
                .containsEntry("queueDepth", 0)
                .containsEntry("shedToReconcile", true);
    }

    @Test
    void healthFailsClosedWhenAnyReadModelBoundaryThrows() {
        SyncHealthIndicator indicator = new SyncHealthIndicator(
                List.of(), List.of(), new SyncHealthState(Clock.fixed(NOW, ZoneOffset.UTC)),
                new PublishLedger() {
                    @Override
                    public PublishRecord ensurePending(PublishRecord pending) {
                        throw new UnsupportedOperationException();
                    }

                    @Override
                    public Optional<PublishRecord> find(String sliceId, String targetId) {
                        return Optional.empty();
                    }

                    @Override
                    public List<PublishRecord> findBySlice(String sliceId) {
                        return List.of();
                    }

                    @Override
                    public List<PublishRecord> findRetryable() {
                        return List.of();
                    }

                    @Override
                    public List<PublishRecord> findRetryable(Instant staleBefore) {
                        return List.of();
                    }

                    @Override
                    public PublishLedgerStatusCounts countByStatus(
                            Optional<String> profile,
                            Optional<String> targetId,
                            Optional<String> endpoint) {
                        throw new UnsupportedOperationException();
                    }

                    @Override
                    public PublishLedgerHealthSummary healthSummary(Set<String> targets) {
                        throw new IllegalStateException("database unavailable");
                    }

                    @Override
                    public List<PublishRecord> findAll() {
                        return List.of();
                    }

                    @Override
                    public PublishRecord transition(
                            String sliceId,
                            String targetId,
                            PublishStatus expected,
                            PublishStatus next,
                            String lastError,
                            String remoteVerification) {
                        throw new UnsupportedOperationException();
                    }
                },
                catalog(List.of()), KeyedSerialExecutorSnapshot::empty,
                descriptor -> false, Clock.fixed(NOW, ZoneOffset.UTC));

        assertThat(indicator.health().getStatus()).isEqualTo(Status.DOWN);
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
            public PublishLedgerHealthSummary healthSummary(Set<String> targetIds) {
                return summary(records, targetIds);
            }

            @Override
            public List<PublishRecord> findAll() {
                throw new AssertionError("health indicator must use aggregate ledger query");
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

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return Clock.fixed(instant, zone);
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }

    private PublishLedgerHealthSummary summary(List<PublishRecord> records, Set<String> targetIds) {
        List<PublishRecord> selected = records.stream()
                .filter(record -> targetIds.contains(record.targetId()))
                .toList();
        Map<String, PublishLedgerStatusCounts> byEndpoint = new LinkedHashMap<>();
        selected.stream().map(record -> record.endpoint()).distinct().forEach(endpoint ->
                byEndpoint.put(endpoint, counts(selected.stream()
                        .filter(record -> record.endpoint().equals(endpoint)).toList())));
        return new PublishLedgerHealthSummary(counts(selected), byEndpoint);
    }

    private PublishLedgerStatusCounts counts(List<PublishRecord> records) {
        return new PublishLedgerStatusCounts(
                records.stream().filter(record -> record.status() == PublishStatus.PENDING).count(),
                records.stream().filter(record -> record.status() == PublishStatus.IN_PROGRESS).count(),
                records.stream().filter(record -> record.status() == PublishStatus.SUCCEEDED).count(),
                records.stream().filter(record -> record.status() == PublishStatus.FAILED).count(),
                records.stream().filter(record -> record.status() == PublishStatus.ABANDONED).count());
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
