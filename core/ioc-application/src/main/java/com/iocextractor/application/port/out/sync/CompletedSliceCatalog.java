package com.iocextractor.application.port.out.sync;

import com.iocextractor.application.sync.CompletedSlice;

import java.util.List;

/**
 * Read-only worklist of locally completed export slices that may be delivered to remote targets.
 */
public interface CompletedSliceCatalog {

    /**
     * Lists verified final slices for one export profile.
     *
     * <p>The implementation must not return staging, incomplete or corrupt final directories as
     * pending work. Corrupt final directories should surface as a failure so operators can
     * investigate local data integrity before remote publication.</p>
     */
    List<CompletedSlice> listCompleted(String profile);
}
