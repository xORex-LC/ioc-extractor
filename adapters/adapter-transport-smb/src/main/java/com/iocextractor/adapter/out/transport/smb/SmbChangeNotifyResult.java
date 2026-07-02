package com.iocextractor.adapter.out.transport.smb;

record SmbChangeNotifyResult(int changeCount, boolean overflow) {

    SmbChangeNotifyResult {
        if (changeCount < 0) {
            throw new IllegalArgumentException("changeCount must not be negative");
        }
    }

    boolean shouldSignal() {
        return overflow || changeCount > 0;
    }
}
