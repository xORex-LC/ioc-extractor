package com.iocextractor.application.export;

import com.iocextractor.application.maintenance.RetentionPolicy;
import com.iocextractor.application.port.in.export.RunSliceRetentionUseCase;
import com.iocextractor.application.port.in.export.SliceRetentionResult;
import com.iocextractor.application.port.out.export.SliceRetentionGuard;
import com.iocextractor.application.port.out.export.SliceRetentionStore;

import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Applies age/count policy independently per profile and honors delivery vetoes at delete time.
 *
 * <p>A blocked candidate remains in the pool, so {@code maxCount} is deliberately best-effort.
 * The store remains responsible for listing and revalidating physical slice directories.
 */
public final class SliceRetentionService implements RunSliceRetentionUseCase {

    private final SliceRetentionStore store;
    private final SliceRetentionGuard guard;
    private final List<String> profiles;
    private final Duration maxAge;
    private final int maxCount;
    private final Clock clock;

    /** Creates a profile-scoped slice retention orchestrator. */
    public SliceRetentionService(SliceRetentionStore store,
                                 SliceRetentionGuard guard,
                                 List<String> profiles,
                                 Duration maxAge,
                                 int maxCount,
                                 Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.guard = Objects.requireNonNull(guard, "guard");
        this.profiles = List.copyOf(Objects.requireNonNull(profiles, "profiles"));
        this.maxAge = maxAge;
        this.maxCount = maxCount;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public SliceRetentionResult run() {
        int scanned = 0;
        int deleted = 0;
        int blocked = 0;
        Map<String, Integer> deletedByProfile = new LinkedHashMap<>();
        for (String profile : profiles) {
            List<SliceDescriptor> slices = store.listCompleted(profile);
            scanned += slices.size();
            List<SliceDescriptor> candidates = RetentionPolicy.selectValues(
                    slices, SliceDescriptor::createdAt, clock.instant(), maxAge, maxCount);
            int profileDeleted = 0;
            for (SliceDescriptor slice : candidates) {
                if (!guard.canDelete(slice)) {
                    blocked++;
                    continue;
                }
                store.delete(slice);
                deleted++;
                profileDeleted++;
            }
            deletedByProfile.put(profile, profileDeleted);
        }
        return new SliceRetentionResult(scanned, deleted, blocked, deletedByProfile);
    }
}
