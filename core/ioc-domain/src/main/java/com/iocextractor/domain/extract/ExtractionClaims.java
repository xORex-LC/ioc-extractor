package com.iocextractor.domain.extract;

/** Invocation-owned global overlap state; storage is chosen outside the domain. */
public interface ExtractionClaims {
    boolean overlaps(int start, int end);
    void record(ExtractionDecision decision);
}
