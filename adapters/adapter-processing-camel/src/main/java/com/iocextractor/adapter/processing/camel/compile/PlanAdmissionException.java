package com.iocextractor.adapter.processing.camel.compile;

/** Rejects a malformed technical plan before any Camel route is started. */
public final class PlanAdmissionException extends IllegalArgumentException {
    public PlanAdmissionException(String location, String reason) {
        super(location + ": " + reason);
    }
}
