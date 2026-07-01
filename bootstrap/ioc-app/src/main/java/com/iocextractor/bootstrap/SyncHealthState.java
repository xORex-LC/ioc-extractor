package com.iocextractor.bootstrap;

import com.iocextractor.application.port.in.sync.ArtifactPublishResult;
import com.iocextractor.application.port.in.sync.RemoteFetchResult;
import com.iocextractor.platform.concurrent.WorkAdmission;
import com.iocextractor.platform.concurrent.WorkKey;

import java.time.Clock;
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
    private final ConcurrentHashMap<String, FetchSnapshot> fetchBySource = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, PublishSnapshot> publishByTarget = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, KeyedExecutorSignal> executorByKey = new ConcurrentHashMap<>();

    /** Creates runtime state timestamped by the injected application clock. */
    public SyncHealthState(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Records a completed source fetch, including partial per-file failures. */
    public void recordFetch(String source, String endpoint, RemoteFetchResult result) {
        Objects.requireNonNull(result, "result");
        fetchBySource.put(source, new FetchSnapshot(
                endpoint, clock.instant(), result.fetched(), result.skipped(), result.failed(), null));
    }

    /** Records a source-level fetch failure that produced no result counters. */
    public void recordFetchFailure(String source, String endpoint, RuntimeException failure) {
        fetchBySource.put(source, new FetchSnapshot(
                endpoint, clock.instant(), 0, 0, 1, failureMessage(failure)));
    }

    /** Records a completed target publish, including failed ledger pairs. */
    public void recordPublish(String target, String endpoint, String profile, ArtifactPublishResult result) {
        Objects.requireNonNull(result, "result");
        publishByTarget.put(target, new PublishSnapshot(
                endpoint, profile, clock.instant(), result.pending(), result.succeeded(),
                result.failed(), result.abandoned(), null));
    }

    /** Records a target-level publish failure that produced no result counters. */
    public void recordPublishFailure(String target,
                                     String endpoint,
                                     String profile,
                                     RuntimeException failure) {
        publishByTarget.put(target, new PublishSnapshot(
                endpoint, profile, clock.instant(), 0, 0, 1, 0, failureMessage(failure)));
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

    /** Returns a stable, key-sorted copy for one health read. */
    public Map<String, PublishSnapshot> publishSnapshots() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(new TreeMap<>(publishByTarget)));
    }

    /** Returns a stable, key-sorted copy of executor degradation signals. */
    public Map<String, KeyedExecutorSignal> keyedExecutorSignals() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(new TreeMap<>(executorByKey)));
    }

    private String failureMessage(RuntimeException failure) {
        Objects.requireNonNull(failure, "failure");
        return failure.getMessage() == null || failure.getMessage().isBlank()
                ? failure.getClass().getSimpleName() : failure.getMessage();
    }

    /** Latest fetch outcome for one configured source. */
    public record FetchSnapshot(String endpoint,
                                Instant completedAt,
                                int fetched,
                                int skipped,
                                int failed,
                                String error) {
    }

    /** Latest publish outcome for one configured target/profile binding. */
    public record PublishSnapshot(String endpoint,
                                  String profile,
                                  Instant completedAt,
                                  int pending,
                                  int succeeded,
                                  int failed,
                                  int abandoned,
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
}
