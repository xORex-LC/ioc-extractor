package com.iocextractor.application.dataframeimport.model;

/** Hard per-stage limits and deterministic shared-workspace watermarks. */
public record ImportWorkspaceLimits(
        long maximumSourceRows,
        int maximumBranchesPerRow,
        int maximumCellsPerBranch,
        long maximumRowErrors,
        long maximumStageBytes,
        long maximumWorkspaceBytes,
        long pauseAtBytes,
        long resumeAtBytes,
        int transactionBatchRows,
        DelimitedInputLimits inputLimits) {

    /** Enforces positive hard bounds and low/high watermark ordering. */
    public ImportWorkspaceLimits {
        if (maximumSourceRows < 1 || maximumBranchesPerRow < 1 || maximumCellsPerBranch < 1
                || maximumRowErrors < 1 || maximumStageBytes < 1 || maximumWorkspaceBytes < maximumStageBytes
                || resumeAtBytes < 0 || pauseAtBytes <= resumeAtBytes || pauseAtBytes >= maximumWorkspaceBytes
                || transactionBatchRows < 1 || inputLimits == null) {
            throw new IllegalArgumentException("Import workspace limits are invalid");
        }
    }

    /** Low-heap defaults sized for the 100k-row P4 qualification baseline. */
    public static ImportWorkspaceLimits defaults() {
        return new ImportWorkspaceLimits(
                1_000_000, 16, 512, 100_000,
                2L * 1024 * 1024 * 1024,
                8L * 1024 * 1024 * 1024,
                6L * 1024 * 1024 * 1024,
                4L * 1024 * 1024 * 1024,
                500,
                DelimitedInputLimits.defaults());
    }
}
