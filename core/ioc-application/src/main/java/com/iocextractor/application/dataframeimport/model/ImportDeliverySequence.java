package com.iocextractor.application.dataframeimport.model;

/** Non-reusable global claim-order sequence for dataframe import deliveries. */
public record ImportDeliverySequence(long value) implements Comparable<ImportDeliverySequence> {

    /** Enforces a positive sequence. */
    public ImportDeliverySequence {
        if (value < 1) {
            throw new IllegalArgumentException("Import delivery sequence must be positive");
        }
    }

    @Override
    public int compareTo(ImportDeliverySequence other) {
        return Long.compare(value, other.value);
    }
}
