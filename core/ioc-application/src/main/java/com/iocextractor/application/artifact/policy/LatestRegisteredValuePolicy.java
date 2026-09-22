package com.iocextractor.application.artifact.policy;

import java.util.Objects;

/** Selects the most recently admitted nonblank value without reading a clock or database. */
public final class LatestRegisteredValuePolicy {

    public FieldUpdateDecision decide(String currentValue, FieldValueOrigin currentOrigin,
                                      String incomingValue, FieldValueOrigin incomingOrigin) {
        Objects.requireNonNull(incomingOrigin, "incomingOrigin");
        if (incomingValue == null || incomingValue.isBlank()) {
            return FieldUpdateDecision.PRESERVE;
        }
        if (currentOrigin == null) {
            return FieldUpdateDecision.CHANGE_PUBLIC_VALUE;
        }
        int comparison = incomingOrigin.compareTo(currentOrigin);
        if (comparison < 0) {
            return FieldUpdateDecision.PRESERVE;
        }
        if (comparison == 0) {
            if (!incomingOrigin.observationId().equals(currentOrigin.observationId())
                    || !incomingValue.equals(currentValue)) {
                throw new IllegalArgumentException("Conflicting evidence at the same field order");
            }
            return FieldUpdateDecision.PRESERVE;
        }
        return incomingValue.equals(currentValue)
                ? FieldUpdateDecision.ADVANCE_ORIGIN_ONLY
                : FieldUpdateDecision.CHANGE_PUBLIC_VALUE;
    }
}
