package com.iocextractor.application.artifact.lifecycle;

import java.util.Objects;

/**
 * Immutable lifecycle facts for one active canonical record identity.
 *
 * @param id service-owned lifecycle identity
 * @param firstConfirmedAt first successful canonical confirmation
 * @param lastConfirmedAt latest successful canonical confirmation
 * @param deadline current absolute validity boundary
 */
public record RecordLifecycle(LifecycleId id,
                              EffectiveTime firstConfirmedAt,
                              EffectiveTime lastConfirmedAt,
                              LifecycleDeadline deadline) {

    /** Enforces ordered confirmation instants and a deadline after the last confirmation. */
    public RecordLifecycle {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(firstConfirmedAt, "firstConfirmedAt");
        Objects.requireNonNull(lastConfirmedAt, "lastConfirmedAt");
        Objects.requireNonNull(deadline, "deadline");
        if (lastConfirmedAt.compareTo(firstConfirmedAt) < 0) {
            throw new IllegalArgumentException("Last confirmation must not precede first confirmation");
        }
        if (!deadline.isActiveAt(lastConfirmedAt)) {
            throw new IllegalArgumentException("Lifecycle deadline must be after last confirmation");
        }
    }

    /** Starts a new lifecycle from an already calculated policy decision. */
    public static RecordLifecycle start(LifecycleId id,
                                        EffectiveTime confirmationTime,
                                        ValidityDecision validity) {
        Objects.requireNonNull(validity, "validity").requireValidAt(confirmationTime);
        return new RecordLifecycle(id, confirmationTime, confirmationTime, validity.deadline());
    }

    /** Returns whether this lifecycle is active at the supplied effective time. */
    public boolean isActiveAt(EffectiveTime asOf) {
        return deadline.isActiveAt(asOf);
    }

    /** Returns whether this lifecycle is due at the supplied effective time. */
    public boolean isDueAt(EffectiveTime asOf) {
        return deadline.isDueAt(asOf);
    }

    /**
     * Renews the same lifecycle after an accepted active confirmation.
     *
     * @throws IllegalStateException when the old lifecycle is already due
     * @throws IllegalArgumentException when confirmation time moves backwards
     */
    public RecordLifecycle renew(EffectiveTime confirmationTime, ValidityDecision validity) {
        Objects.requireNonNull(confirmationTime, "confirmationTime");
        Objects.requireNonNull(validity, "validity").requireValidAt(confirmationTime);
        if (isDueAt(confirmationTime)) {
            throw new IllegalStateException("A due lifecycle cannot be renewed");
        }
        if (confirmationTime.compareTo(lastConfirmedAt) < 0) {
            throw new IllegalArgumentException("Confirmation time must not move backwards");
        }
        return new RecordLifecycle(id, firstConfirmedAt, confirmationTime, validity.deadline());
    }
}
