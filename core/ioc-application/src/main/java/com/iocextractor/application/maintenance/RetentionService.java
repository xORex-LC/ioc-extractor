package com.iocextractor.application.maintenance;

import com.iocextractor.application.port.in.maintenance.RetentionResult;
import com.iocextractor.application.port.in.maintenance.RunRetentionUseCase;
import com.iocextractor.application.port.out.maintenance.RetentionStore;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Use case that sweeps every configured {@link RetentionTarget} once: list its
 * entries via the {@link RetentionStore}, ask the pure {@link RetentionPolicy} which
 * are expired, and delete/archive them. The clock is injected so behavior is
 * deterministic under test.
 */
public final class RetentionService implements RunRetentionUseCase {

    private final RetentionStore store;
    private final List<RetentionTarget> targets;
    private final Clock clock;

    public RetentionService(RetentionStore store, List<RetentionTarget> targets, Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.targets = List.copyOf(Objects.requireNonNull(targets, "targets"));
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public RetentionResult run() {
        Instant now = clock.instant();
        int scanned = 0;
        int reaped = 0;
        Map<String, Integer> reapedByTarget = new LinkedHashMap<>();
        for (RetentionTarget target : targets) {
            List<RetentionEntry> entries = store.list(target.dir());
            scanned += entries.size();
            List<RetentionEntry> expired =
                    RetentionPolicy.select(entries, now, target.maxAge(), target.maxCount());
            for (RetentionEntry entry : expired) {
                if (target.action() == RetentionAction.ARCHIVE) {
                    store.archive(entry, target.archiveDir());
                } else {
                    store.delete(entry);
                }
            }
            reaped += expired.size();
            reapedByTarget.put(target.name(), expired.size());
        }
        return new RetentionResult(scanned, reaped, reapedByTarget);
    }
}
