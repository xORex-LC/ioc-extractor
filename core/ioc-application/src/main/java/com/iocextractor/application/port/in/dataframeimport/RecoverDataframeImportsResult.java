package com.iocextractor.application.port.in.dataframeimport;

/** Safe aggregate result of startup or periodic import reconciliation. */
public record RecoverDataframeImportsResult(int examined, int advanced, int contradictions) {

    /** Enforces non-negative recovery counts. */
    public RecoverDataframeImportsResult {
        if (examined < 0 || advanced < 0 || contradictions < 0 || advanced > examined) {
            throw new IllegalArgumentException("Invalid dataframe import recovery counts");
        }
    }
}
