package com.iocextractor.application.sync;

import java.util.Map;
import java.util.Objects;

/** Aggregate durable publish state used by operational read models. */
public record PublishLedgerHealthSummary(PublishLedgerStatusCounts totals,
                                         Map<String, PublishLedgerStatusCounts> byEndpoint) {

    public PublishLedgerHealthSummary {
        Objects.requireNonNull(totals, "totals");
        byEndpoint = Map.copyOf(Objects.requireNonNull(byEndpoint, "byEndpoint"));
    }

    /** Returns an empty durable summary. */
    public static PublishLedgerHealthSummary empty() {
        return new PublishLedgerHealthSummary(
                new PublishLedgerStatusCounts(0, 0, 0, 0, 0), Map.of());
    }
}
