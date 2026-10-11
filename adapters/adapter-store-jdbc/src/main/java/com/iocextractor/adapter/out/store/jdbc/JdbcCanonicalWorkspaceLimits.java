package com.iocextractor.adapter.out.store.jdbc;

/** Byte-bounded canonical working sets; public row/field and aggregate budgets are preserved. */
final class JdbcCanonicalWorkspaceLimits {
    private JdbcCanonicalWorkspaceLimits() { }

    static DocumentPreparationLimits privateLimits(DocumentPreparationLimits limits) {
        return new DocumentPreparationLimits(limits.memoryBytes(), Math.min(limits.cacheKiB(), 512),
                limits.maximumRowBytes(), limits.maximumFieldBytes(), limits.workspaceBytes(),
                limits.totalDiskBytes(), limits.batchRows());
    }

    static int chunkBytes(DocumentPreparationLimits limits) {
        return Math.toIntExact(Math.min(1024 * 1024L, Math.max(65536L,
                Math.max(2L * limits.maximumRowBytes() + 65536, limits.cacheKiB() * 512L))));
    }

    static long memoryBytes(DocumentPreparationLimits limits) {
        // UTF-16 objects, JDBC bindings and checksum encodings coexist with the
        // chunk and current/previous row. Six bounded main/TEMP caches are separate.
        return Math.addExact(8L * (2L * limits.maximumRowBytes() + chunkBytes(limits)),
                limits.cacheKiB() * 6144L + 65536);
    }
}
