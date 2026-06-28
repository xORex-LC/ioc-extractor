package com.iocextractor.application.port.in.export;

import java.util.Map;

/** Outcome of one slice-level retention sweep. */
public record SliceRetentionResult(int scanned,
                                   int deleted,
                                   int blocked,
                                   Map<String, Integer> deletedByProfile) {

    public SliceRetentionResult {
        if (scanned < 0 || deleted < 0 || blocked < 0 || deleted + blocked > scanned) {
            throw new IllegalArgumentException("Invalid slice retention counters");
        }
        deletedByProfile = Map.copyOf(deletedByProfile);
    }
}
