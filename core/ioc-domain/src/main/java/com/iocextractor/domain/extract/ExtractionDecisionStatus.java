package com.iocextractor.domain.extract;

/** Outcome of one raw regex match before source attribution. */
public enum ExtractionDecisionStatus {
    ACCEPTED,
    DROPPED_OVERLAP
}
