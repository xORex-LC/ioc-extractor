package com.iocextractor.adapter.out.transport.smb;

interface SmbShareClientFactory {

    SmbShareClient open(SmbEndpointSettings settings);
}
