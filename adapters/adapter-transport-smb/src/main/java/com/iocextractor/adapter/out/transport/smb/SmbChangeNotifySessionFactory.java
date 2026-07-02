package com.iocextractor.adapter.out.transport.smb;

interface SmbChangeNotifySessionFactory {

    SmbChangeNotifySession open(SmbEndpointSettings settings, String remotePath);
}
