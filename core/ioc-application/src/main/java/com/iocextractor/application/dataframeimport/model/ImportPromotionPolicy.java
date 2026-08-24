package com.iocextractor.application.dataframeimport.model;

import java.util.Objects;
import java.util.Optional;

/** Delivery policy that must remain reproducible after the live catalog changes. */
public record ImportPromotionPolicy(
        ImportRowFailurePolicy rowFailurePolicy,
        boolean renewUnchanged,
        Optional<ImportRequestedSlotPolicy> requestedSlotPolicy) {

    /** Snapshots every promotion-time branch decision. */
    public ImportPromotionPolicy {
        Objects.requireNonNull(rowFailurePolicy, "rowFailurePolicy");
        requestedSlotPolicy = Objects.requireNonNull(requestedSlotPolicy, "requestedSlotPolicy");
    }

    /** Conservative compatibility policy for manually constructed staging fixtures. */
    public static ImportPromotionPolicy defaults() {
        return new ImportPromotionPolicy(ImportRowFailurePolicy.ACCEPT_VALID, false, Optional.empty());
    }
}
