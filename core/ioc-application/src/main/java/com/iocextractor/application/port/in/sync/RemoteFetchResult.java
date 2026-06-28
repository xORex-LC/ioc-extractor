package com.iocextractor.application.port.in.sync;

/** Operator-facing summary of one fetch cycle. */
public record RemoteFetchResult(int fetched, int skipped, int failed) {

    public RemoteFetchResult {
        if (fetched < 0 || skipped < 0 || failed < 0) {
            throw new IllegalArgumentException("fetch counters must not be negative");
        }
    }
}
