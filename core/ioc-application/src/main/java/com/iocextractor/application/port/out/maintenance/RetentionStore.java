package com.iocextractor.application.port.out.maintenance;

import com.iocextractor.application.maintenance.RetentionEntry;

import java.nio.file.Path;
import java.util.List;

/**
 * Driven port for the storage a {@link com.iocextractor.application.maintenance.RetentionPolicy}
 * acts on. The default implementation is filesystem-backed, but the policy/use case
 * stay storage-agnostic (a future object-store reaper is just another adapter).
 */
public interface RetentionStore {

    /**
     * List the top-level reapable entries under {@code dir}. A missing directory
     * yields an empty list (nothing to reap yet), never an error.
     */
    List<RetentionEntry> list(Path dir);

    /** Permanently remove the entry. */
    void delete(RetentionEntry entry);

    /** Move the entry into {@code archiveDir}. */
    void archive(RetentionEntry entry, Path archiveDir);
}
