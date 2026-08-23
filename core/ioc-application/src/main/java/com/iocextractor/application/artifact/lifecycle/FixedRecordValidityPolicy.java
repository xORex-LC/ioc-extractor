package com.iocextractor.application.artifact.lifecycle;

import java.time.Duration;
import java.util.Objects;

/** V1 record-validity strategy that applies one strictly positive duration. */
public final class FixedRecordValidityPolicy implements RecordValidityPolicy {

    private final Duration ttl;

    /**
     * Creates the fixed policy.
     *
     * @param ttl strictly positive record validity duration
     */
    public FixedRecordValidityPolicy(Duration ttl) {
        this.ttl = Objects.requireNonNull(ttl, "ttl");
        if (ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("Fixed record TTL must be positive");
        }
    }

    /** Returns the configured fixed duration. */
    public Duration ttl() {
        return ttl;
    }

    @Override
    public ValidityDecision decide(EffectiveTime confirmationTime) {
        Objects.requireNonNull(confirmationTime, "confirmationTime");
        var decision = new ValidityDecision(
                new LifecycleDeadline(confirmationTime.value().plus(ttl)));
        return decision.requireValidAt(confirmationTime);
    }
}
