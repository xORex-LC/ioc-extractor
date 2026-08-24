package com.iocextractor.application.dataframeimport.model;

import java.util.Objects;

/** Immutable requested export-slot policy pinned into a sealed import stage. */
public record ImportRequestedSlotPolicy(
        String profile,
        ImportExistingSlotPolicy existingRecordPolicy) {

    /** Requires a named profile and an explicit survivor policy. */
    public ImportRequestedSlotPolicy {
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(existingRecordPolicy, "existingRecordPolicy");
        if (profile.isBlank()) {
            throw new IllegalArgumentException("Import requested-slot profile must not be blank");
        }
    }
}
