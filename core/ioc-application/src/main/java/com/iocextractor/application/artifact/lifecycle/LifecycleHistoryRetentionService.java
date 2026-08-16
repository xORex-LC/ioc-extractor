package com.iocextractor.application.artifact.lifecycle;

import com.iocextractor.application.port.in.artifact.lifecycle.RunLifecycleHistoryRetentionUseCase;
import com.iocextractor.application.port.out.artifact.lifecycle.LifecycleHistoryStore;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Independent bounded retention of historical lifecycle snapshots. */
public final class LifecycleHistoryRetentionService implements RunLifecycleHistoryRetentionUseCase {

    private final List<String> artifacts;
    private final LifecycleHistoryStore history;
    private final LifecycleTimeSource timeSource;
    private final Duration retention;
    private final int batchSize;

    public LifecycleHistoryRetentionService(List<String> artifacts,
                                            LifecycleHistoryStore history,
                                            LifecycleTimeSource timeSource,
                                            Duration retention,
                                            int batchSize) {
        this.artifacts = requireArtifacts(artifacts);
        this.history = Objects.requireNonNull(history, "history");
        this.timeSource = Objects.requireNonNull(timeSource, "timeSource");
        this.retention = requirePositive(retention, "retention");
        if (batchSize <= 0) {
            throw new IllegalArgumentException("batchSize must be positive");
        }
        this.batchSize = batchSize;
    }

    @Override
    public LifecycleHistoryRetentionResult run() {
        EffectiveTime cutoff = EffectiveTime.at(timeSource.now().value().minus(retention));
        Map<String, Integer> purged = new LinkedHashMap<>();
        boolean moreEligible = false;
        int total = 0;
        for (String artifact : artifacts) {
            LifecycleHistoryStore.HistoryPurgeResult batch = history.purge(artifact, cutoff, batchSize);
            if (batch.purged() > 0) {
                purged.put(artifact, batch.purged());
                total = Math.addExact(total, batch.purged());
            }
            moreEligible |= batch.moreEligible();
        }
        return new LifecycleHistoryRetentionResult(total, moreEligible, purged);
    }

    private static Duration requirePositive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    private static List<String> requireArtifacts(List<String> source) {
        Objects.requireNonNull(source, "artifacts");
        Set<String> unique = new LinkedHashSet<>();
        for (String artifact : source) {
            if (artifact == null || artifact.isBlank() || !unique.add(artifact)) {
                throw new IllegalArgumentException("Artifact catalog must contain unique non-blank names");
            }
        }
        return List.copyOf(source);
    }
}
