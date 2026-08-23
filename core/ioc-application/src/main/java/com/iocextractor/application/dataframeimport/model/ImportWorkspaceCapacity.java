package com.iocextractor.application.dataframeimport.model;

/** Aggregate workspace usage and hysteretic intake state without path disclosure. */
public record ImportWorkspaceCapacity(long usedBytes, long hardLimitBytes, State state) {

    /** Capacity state used by intake admission. */
    public enum State {
        /** New staging work may be admitted. */
        ACCEPTING,
        /** Admission pauses until usage falls below the resume watermark. */
        PAUSED,
        /** Hard safety capacity is exhausted. */
        EXHAUSTED
    }

    /** Enforces non-negative bounded aggregate usage. */
    public ImportWorkspaceCapacity {
        if (usedBytes < 0 || hardLimitBytes < 1 || state == null) {
            throw new IllegalArgumentException("Import workspace capacity is invalid");
        }
    }
}
