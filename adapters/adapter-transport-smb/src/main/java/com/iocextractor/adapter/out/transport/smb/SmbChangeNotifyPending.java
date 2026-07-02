package com.iocextractor.adapter.out.transport.smb;

interface SmbChangeNotifyPending {

    boolean isDone();

    SmbChangeNotifyResult get();

    boolean cancel();
}
