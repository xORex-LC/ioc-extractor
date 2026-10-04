package com.iocextractor.adapter.out.store.jdbc;

/** Shared admission and per-item limits; native caches are accounted together across jobs. */
public record DocumentPreparationLimits(long memoryBytes, int cacheKiB, int maximumRowBytes,
                                        int maximumFieldBytes, long workspaceBytes,
                                        long totalDiskBytes, int batchRows) {
    public DocumentPreparationLimits {
        if (cacheKiB < 64 || maximumFieldBytes < 1 || maximumRowBytes < maximumFieldBytes
                || maximumRowBytes > 16 * 1024 * 1024 || batchRows < 1 || batchRows > 4096
                || workspaceBytes < 65536 || totalDiskBytes < workspaceBytes
                || memoryBytes < cacheKiB * 1024L + maximumRowBytes * 8L + 65536) {
            throw new IllegalArgumentException("Invalid document preparation limits");
        }
    }

    /** Includes a conservative maximum codec/cursor working set in every live lease. */
    public long leaseBytes() { return cacheKiB * 1024L + maximumRowBytes * 8L + 65536; }
}
