package com.iocextractor.adapter.out.transport.smb;

import java.time.Duration;
import java.util.Arrays;

final class SmbContractTestSupport {

    private SmbContractTestSupport() {
    }

    static SmbEndpointSettings settings() {
        return settings(Duration.ofSeconds(30));
    }

    static SmbEndpointSettings settings(Duration requestTimeout) {
        char[] password = require("ioc.smb.password").toCharArray();
        try {
            return new SmbEndpointSettings(
                    "contract",
                    require("ioc.smb.host"),
                    port(),
                    require("ioc.smb.share"),
                    System.getProperty("ioc.smb.domain", ""),
                    require("ioc.smb.username"),
                    password,
                    encryptionPolicy(),
                    Duration.ofSeconds(5),
                    requestTimeout,
                    Duration.ofMinutes(1));
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    static SmbEncryptionPolicy encryptionPolicy() {
        String configured = System.getProperty("ioc.smb.encryption", "required");
        return SmbEncryptionPolicy.valueOf(configured.toUpperCase(java.util.Locale.ROOT));
    }

    static int port() {
        return Integer.parseInt(System.getProperty(
                "ioc.smb.port", Integer.toString(SmbEndpointSettings.DEFAULT_PORT)));
    }

    static String remoteRoot() {
        return require("ioc.smb.remotePath");
    }

    static HeldWriteHandle holdWriterWithoutDeleteSharing(
            SmbShareClient client,
            String remotePath) {
        if (client instanceof SmbjShareClient smbjClient) {
            return new HeldWriteHandle(smbjClient.openWriteHandleWithoutDeleteSharing(remotePath));
        }
        throw new IllegalArgumentException("live contract requires an SMBJ share client");
    }

    private static String require(String property) {
        String value = System.getProperty(property);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing required system property: " + property);
        }
        return value;
    }

    record HeldWriteHandle(com.hierynomus.smbj.share.File file)
            implements AutoCloseable {

        @Override
        public void close() {
            file.close();
        }
    }
}
