package com.iocextractor.application.artifact;

import java.util.Set;

/** Safe mutation result containing column names and identities, never IOC values. */
public record CanonicalRecordMutationOutcome(CanonicalRecordMutationKind kind,
                                             long canonicalRowId,
                                             long lifecycleId,
                                             Set<String> updatedFields,
                                             Set<String> clearedFields) {

    public CanonicalRecordMutationOutcome {
        if (kind == null) {
            throw new NullPointerException("kind");
        }
        if (canonicalRowId <= 0 || lifecycleId <= 0) {
            throw new IllegalArgumentException("Canonical mutation identities must be positive");
        }
        updatedFields = Set.copyOf(updatedFields);
        clearedFields = Set.copyOf(clearedFields);
        if (!java.util.Collections.disjoint(updatedFields, clearedFields)) {
            throw new IllegalArgumentException("Updated and cleared fields must be disjoint");
        }
    }

    /** Returns whether public bytes changed and therefore revision work is required. */
    public boolean publicMutation() {
        return kind == CanonicalRecordMutationKind.INSERTED
                || kind == CanonicalRecordMutationKind.RESTARTED
                || kind == CanonicalRecordMutationKind.UPDATED
                || kind == CanonicalRecordMutationKind.CLEARED;
    }
}
