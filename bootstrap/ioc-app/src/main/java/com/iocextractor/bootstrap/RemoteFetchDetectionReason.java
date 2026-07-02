package com.iocextractor.bootstrap;

/** Reason for a remote fetch detection request. */
enum RemoteFetchDetectionReason {
    PERIODIC(true),
    PUSH(false),
    STARTUP(true),
    WATCH_ESTABLISHED(true);

    private final boolean immediate;

    RemoteFetchDetectionReason(boolean immediate) {
        this.immediate = immediate;
    }

    boolean immediate() {
        return immediate;
    }
}
