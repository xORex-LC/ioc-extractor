package com.iocextractor.bootstrap;

import com.iocextractor.application.port.in.sync.ArtifactPublishExecutionResult;
import com.iocextractor.application.port.in.sync.RemoteFetchResult;
import com.iocextractor.platform.concurrent.WorkAdmission;
import com.iocextractor.platform.concurrent.WorkKey;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/** Thread-safe latest-run state shared by sync schedulers and the actuator health read model. */
public final class SyncHealthState {

    private final Clock clock;
    private final SyncOperationalOutcomePolicy outcomePolicy;
    private final ConcurrentHashMap<String, FetchSnapshot> fetchBySource = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, FetchDetectionSnapshot> detectionBySource = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, PublishSnapshot> publishByTarget = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, KeyedExecutorSignal> executorByKey = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, RemoteChangeWatchSnapshot> watchBySource = new ConcurrentHashMap<>();

    /** Creates runtime state timestamped by the injected application clock. */
    public SyncHealthState(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.outcomePolicy = new SyncOperationalOutcomePolicy();
    }

    /** Records a completed source fetch, including partial per-file failures. */
    public void recordFetch(String source, String endpoint, RemoteFetchResult result) {
        Objects.requireNonNull(result, "result");
        fetchBySource.put(source, new FetchSnapshot(
                endpoint, clock.instant(), result.fetched(), result.skipped(), result.failed(),
                result.failed() == 0 ? SyncOperationalStatus.UP : SyncOperationalStatus.DOWN, null));
    }

    /** Records a source-level fetch failure that produced no result counters. */
    public void recordFetchFailure(String source, String endpoint, RuntimeException failure) {
        fetchBySource.put(source, new FetchSnapshot(
                endpoint, clock.instant(), 0, 0, 1, outcomePolicy.classify(failure), failureMessage(failure)));
    }

    /** Records a completed remote source detection run. */
    public void recordFetchDetection(String source,
                                     String endpoint,
                                     String reason,
                                     int detectedObjects,
                                     Duration duration) {
        detectionBySource.compute(source, (ignored, previous) -> new FetchDetectionSnapshot(
                endpoint, clock.instant(), reason, Math.max(0, detectedObjects),
                previous == null ? 0 : previous.coalescedSignals(),
                requireNonNegative(duration, "duration"), SyncOperationalStatus.UP, null));
    }

    /** Records a remote source detection run failure. */
    public void recordFetchDetectionFailure(String source,
                                            String endpoint,
                                            String reason,
                                            RuntimeException failure,
                                            Duration duration) {
        detectionBySource.compute(source, (ignored, previous) -> new FetchDetectionSnapshot(
                endpoint, clock.instant(), reason, 0,
                previous == null ? 0 : previous.coalescedSignals(),
                requireNonNegative(duration, "duration"), outcomePolicy.classify(failure), failureMessage(failure)));
    }

    /** Records a signal that was coalesced into an already scheduled or running detection. */
    public void recordFetchDetectionCoalesced(String source, String endpoint) {
        detectionBySource.compute(source, (ignored, previous) -> new FetchDetectionSnapshot(
                endpoint, previous == null ? clock.instant() : previous.completedAt(),
                previous == null ? "UNKNOWN" : previous.reason(),
                previous == null ? 0 : previous.detectedObjects(),
                previous == null ? 1 : previous.coalescedSignals() + 1,
                previous == null ? Duration.ZERO : previous.duration(),
                previous == null ? SyncOperationalStatus.UP : previous.status(),
                previous == null ? null : previous.error()));
    }

    /** Records an active optional remote change watch. */
    public void recordRemoteChangeWatchEstablished(String source, String endpoint) {
        watchBySource.compute(source, (ignored, previous) -> new RemoteChangeWatchSnapshot(
                endpoint, clock.instant(), RemoteChangeWatchStatus.ACTIVE,
                previous == null ? 0 : previous.signals(),
                previous == null ? 0 : previous.reconnects(),
                previous == null || !previous.everEstablished() ? 0 : previous.reArms() + 1,
                null,
                true));
    }

    /** Records an optional remote change watch signal. */
    public void recordRemoteChangeWatchSignal(String source, String endpoint) {
        watchBySource.compute(source, (ignored, previous) -> new RemoteChangeWatchSnapshot(
                endpoint, clock.instant(),
                previous == null ? RemoteChangeWatchStatus.ACTIVE : previous.status(),
                previous == null ? 1 : previous.signals() + 1,
                previous == null ? 0 : previous.reconnects(),
                previous == null ? 0 : previous.reArms(),
                previous == null ? null : previous.error(),
                previous != null && previous.everEstablished()));
    }

    /** Records an optional remote change watch entering reconnect/degraded state. */
    public void recordRemoteChangeWatchFailure(String source, String endpoint, RuntimeException failure) {
        watchBySource.compute(source, (ignored, previous) -> new RemoteChangeWatchSnapshot(
                endpoint, clock.instant(), RemoteChangeWatchStatus.RECONNECTING,
                previous == null ? 0 : previous.signals(),
                previous == null ? 1 : previous.reconnects() + 1,
                previous == null ? 0 : previous.reArms(),
                failureMessage(failure),
                previous != null && previous.everEstablished()));
    }

    /** Records that remote change watch is disabled for one source. */
    public void recordRemoteChangeWatchDisabled(String source, String endpoint) {
        watchBySource.put(source, new RemoteChangeWatchSnapshot(
                endpoint, clock.instant(), RemoteChangeWatchStatus.DISABLED, 0, 0, 0, null, false));
    }

    /** Records a completed target publish, including failed ledger pairs. */
    public void recordPublish(String target, String endpoint, String profile, ArtifactPublishExecutionResult result) {
        Objects.requireNonNull(result, "result");
        publishByTarget.put(target, new PublishSnapshot(
                endpoint, profile, clock.instant(), result.attempted(), result.succeeded(),
                result.recovered(), result.failed(),
                result.failed() == 0 ? SyncOperationalStatus.UP : SyncOperationalStatus.DOWN, null));
    }

    /** Records a target-level publish failure that produced no result counters. */
    public void recordPublishFailure(String target,
                                     String endpoint,
                                     String profile,
                                     RuntimeException failure) {
        publishByTarget.put(target, new PublishSnapshot(
                endpoint, profile, clock.instant(), 0, 0, 0, 1,
                outcomePolicy.classify(failure), failureMessage(failure)));
    }

    /** Records admission shedding for one in-memory executor key. */
    public void recordKeyedRejection(WorkAdmission admission) {
        Objects.requireNonNull(admission, "admission");
        executorByKey.compute(admission.key().value(), (ignored, previous) -> new KeyedExecutorSignal(
                clock.instant(),
                true,
                admission.queuedDepth(),
                previous == null ? 0 : previous.abandonedWork(),
                previous == null ? null : previous.lastDispatchFailure(),
                previous == null ? null : previous.error()));
    }

    /** Clears transient executor degradation after the key completes later work normally. */
    public boolean recordKeyedSuccess(WorkKey key) {
        Objects.requireNonNull(key, "key");
        return executorByKey.remove(key.value()) != null;
    }

    /** Records an accepted work item failure for one in-memory executor key. */
    public void recordKeyedFailure(WorkKey key, RuntimeException failure) {
        Objects.requireNonNull(key, "key");
        executorByKey.compute(key.value(), (ignored, previous) -> new KeyedExecutorSignal(
                clock.instant(),
                previous != null && previous.shedToReconcile(),
                previous == null ? 0 : previous.rejectedQueuedDepth(),
                previous == null ? 0 : previous.abandonedWork(),
                previous == null ? null : previous.lastDispatchFailure(),
                failureMessage(failure)));
    }

    /** Records backing executor rejection after work had already been accepted. */
    public void recordKeyedDispatchRejected(WorkKey key,
                                            int abandonedWork,
                                            RuntimeException failure) {
        Objects.requireNonNull(key, "key");
        executorByKey.compute(key.value(), (ignored, previous) -> new KeyedExecutorSignal(
                clock.instant(),
                true,
                previous == null ? 0 : previous.rejectedQueuedDepth(),
                abandonedWork,
                failureMessage(failure),
                previous == null ? null : previous.error()));
    }

    /** Returns a stable, key-sorted copy for one health read. */
    public Map<String, FetchSnapshot> fetchSnapshots() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(new TreeMap<>(fetchBySource)));
    }

    /** Returns a stable, key-sorted copy of latest detection outcomes. */
    public Map<String, FetchDetectionSnapshot> fetchDetectionSnapshots() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(new TreeMap<>(detectionBySource)));
    }

    /** Returns a stable, key-sorted copy for one health read. */
    public Map<String, PublishSnapshot> publishSnapshots() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(new TreeMap<>(publishByTarget)));
    }

    /** Returns a stable, key-sorted copy of executor degradation signals. */
    public Map<String, KeyedExecutorSignal> keyedExecutorSignals() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(new TreeMap<>(executorByKey)));
    }

    /** Returns a stable, key-sorted copy of optional remote change watch state. */
    public Map<String, RemoteChangeWatchSnapshot> remoteChangeWatchSnapshots() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(new TreeMap<>(watchBySource)));
    }

    private String failureMessage(RuntimeException failure) {
        Objects.requireNonNull(failure, "failure");
        return failure.getMessage() == null || failure.getMessage().isBlank()
                ? failure.getClass().getSimpleName() : failure.getMessage();
    }

    private Duration requireNonNegative(Duration value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isNegative()) {
            throw new IllegalArgumentException(field + " must not be negative");
        }
        return value;
    }

    /** Latest fetch outcome for one configured source. */
    public record FetchSnapshot(String endpoint,
                                Instant completedAt,
                                int fetched,
                                int skipped,
                                int failed,
                                SyncOperationalStatus status,
                                String error) {
    }

    /** Latest detection outcome for one configured source. */
    public record FetchDetectionSnapshot(String endpoint,
                                         Instant completedAt,
                                         String reason,
                                         int detectedObjects,
                                         long coalescedSignals,
                                         Duration duration,
                                         SyncOperationalStatus status,
                                         String error) {
    }

    /** Latest publish outcome for one configured target/profile binding. */
    public record PublishSnapshot(String endpoint,
                                  String profile,
                                  Instant completedAt,
                                  int attempted,
                                  int succeeded,
                                  int recovered,
                                  int failed,
                                  SyncOperationalStatus status,
                                  String error) {
    }

    /** Latest degradation signal for one in-memory keyed executor lane. */
    public record KeyedExecutorSignal(Instant updatedAt,
                                      boolean shedToReconcile,
                                      int rejectedQueuedDepth,
                                      int abandonedWork,
                                      String lastDispatchFailure,
                                      String error) {
    }

    /** Latest optional remote change watch state for one configured source. */
    public record RemoteChangeWatchSnapshot(String endpoint,
                                            Instant updatedAt,
                                            RemoteChangeWatchStatus status,
                                            long signals,
                                            long reconnects,
                                            long reArms,
                                            String error,
                                            boolean everEstablished) {
    }
}
