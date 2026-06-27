package com.iocextractor.application.port.out.export;

import com.iocextractor.application.export.SliceDescriptor;

/** Optional delivery-aware veto evaluated immediately before deleting a completed slice. */
public interface SliceRetentionGuard {

    boolean canDelete(SliceDescriptor slice);
}
