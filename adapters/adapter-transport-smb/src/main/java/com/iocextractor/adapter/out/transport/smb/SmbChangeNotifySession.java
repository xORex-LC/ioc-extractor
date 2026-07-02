package com.iocextractor.adapter.out.transport.smb;

interface SmbChangeNotifySession extends AutoCloseable {

    SmbChangeNotifyPending watch();

    @Override
    void close();
}
