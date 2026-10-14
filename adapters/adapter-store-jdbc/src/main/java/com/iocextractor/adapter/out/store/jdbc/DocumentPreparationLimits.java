package com.iocextractor.adapter.out.store.jdbc;

/** Shared admission and per-item limits; native caches are accounted together across jobs. */
public record DocumentPreparationLimits(long memoryBytes, int cacheKiB, int maximumRowBytes,
                                        int maximumFieldBytes, long workspaceBytes,
                                        long totalDiskBytes, int batchRows) {
    private static final int MINIMUM_CACHE_KIB = 64;
    private static final int MAXIMUM_ROW_BYTES = 16 * 1024 * 1024;
    private static final int MAXIMUM_BATCH_ROWS = 4096;
    private static final long MINIMUM_WORKSPACE_BYTES = 64 * 1024L;
    private static final long BYTES_PER_KIB = 1024;
    private static final long WORKING_ROW_ALLOWANCE = 64;
    private static final long FIXED_BUFFER_BYTES = 64 * 1024L;

    public DocumentPreparationLimits {
        if (cacheKiB < MINIMUM_CACHE_KIB || maximumFieldBytes < 1 || maximumRowBytes < maximumFieldBytes
                || maximumRowBytes > MAXIMUM_ROW_BYTES || batchRows < 1 || batchRows > MAXIMUM_BATCH_ROWS
                || workspaceBytes < MINIMUM_WORKSPACE_BYTES || totalDiskBytes < workspaceBytes
                || memoryBytes < cacheKiB * BYTES_PER_KIB + maximumRowBytes * WORKING_ROW_ALLOWANCE + FIXED_BUFFER_BYTES) {
            throw new IllegalArgumentException("Invalid document preparation limits");
        }
    }

    /**
     * Admission estimate for parser/codec working rows, native caches and fixed text buffers.
     * This is a reservation policy, not a precise object-size or RSS guarantee. Validation
     * requires enough memory for one lease, so a valid configuration cannot have zero permits.
     */
    public long leaseBytes() { return cacheKiB * BYTES_PER_KIB + maximumRowBytes * WORKING_ROW_ALLOWANCE + FIXED_BUFFER_BYTES; }
}
