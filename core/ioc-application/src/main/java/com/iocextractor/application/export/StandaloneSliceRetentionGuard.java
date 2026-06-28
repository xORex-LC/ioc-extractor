package com.iocextractor.application.export;

import com.iocextractor.application.port.out.export.SliceRetentionGuard;

/** Standalone composition used when no remote publish targets can pin a local slice. */
public enum StandaloneSliceRetentionGuard implements SliceRetentionGuard {
    INSTANCE;

    @Override
    public boolean canDelete(SliceDescriptor slice) {
        return true;
    }
}
