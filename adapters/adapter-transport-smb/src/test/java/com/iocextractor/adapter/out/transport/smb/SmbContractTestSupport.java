package com.iocextractor.adapter.out.transport.smb;

import java.time.Duration;
import java.util.Arrays;
import java.util.Map;

final class SmbContractTestSupport {

    private SmbContractTestSupport() {
    }

    static SmbEndpointSettings settings() {
        return settings(Duration.ofSeconds(30));
    }

    static SmbEndpointSettings settings(Duration requestTimeout) {
        Map<String, String> environment = System.getenv();
        CredentialProfile credentials = serviceCredentialProfile(environment);
        char[] password = credentials.password(environment).toCharArray();
        try {
            return new SmbEndpointSettings(
                    "contract",
                    requireSystemProperty("ioc.smb.host"),
                    port(),
                    requireSystemProperty("ioc.smb.share"),
                    credentials.domain(environment),
                    credentials.username(environment),
                    password,
                    encryptionPolicy(),
                    Duration.ofSeconds(5),
                    requestTimeout,
                    Duration.ofMinutes(1));
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    static SmbEndpointSettings producerSettings() {
        Map<String, String> environment = System.getenv();
        CredentialProfile credentials = requireCompleteProfile(
                environment, CredentialProfile.PRODUCER);
        char[] password = credentials.password(environment).toCharArray();
        try {
            return new SmbEndpointSettings(
                    "producer-contract",
                    requireSystemProperty("ioc.smb.host"),
                    port(),
                    requireSystemProperty("ioc.smb.share"),
                    credentials.domain(environment),
                    credentials.username(environment),
                    password,
                    encryptionPolicy(),
                    Duration.ofSeconds(5),
                    Duration.ofSeconds(30),
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
        return requireSystemProperty("ioc.smb.remotePath");
    }

    static HeldWriteHandle holdWriterWithoutDeleteSharing(
            SmbShareClient client,
            String remotePath) {
        if (client instanceof SmbjShareClient smbjClient) {
            return new HeldWriteHandle(smbjClient.openWriteHandleWithoutDeleteSharing(remotePath));
        }
        throw new IllegalArgumentException("live contract requires an SMBJ share client");
    }

    static CredentialProfile serviceCredentialProfile(Map<String, String> environment) {
        return requireCompleteProfile(
                environment, CredentialProfile.STANDARD, CredentialProfile.SERVICE);
    }

    private static CredentialProfile requireCompleteProfile(
            Map<String, String> environment,
            CredentialProfile... candidates) {
        for (CredentialProfile candidate : candidates) {
            if (candidate.isComplete(environment)) {
                return candidate;
            }
            if (candidate.isPartiallyConfigured(environment)) {
                throw new IllegalStateException(
                        "Incomplete SMB contract credentials: set both "
                                + candidate.usernameVariable + " and "
                                + candidate.passwordVariable);
            }
        }
        throw new IllegalStateException(
                "Missing SMB contract credentials: set one complete environment pair: "
                        + Arrays.stream(candidates)
                        .map(CredentialProfile::requiredPair)
                        .reduce((left, right) -> left + " or " + right)
                        .orElseThrow());
    }

    private static String requireSystemProperty(String property) {
        String value = System.getProperty(property);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing required system property: " + property);
        }
        return value;
    }

    enum CredentialProfile {
        STANDARD("SMB_USER", "SMB_PASSWORD", "SMB_DOMAIN"),
        SERVICE("SMB_SERVICE_USER", "SMB_SERVICE_PASSWORD", "SMB_SERVICE_DOMAIN"),
        PRODUCER("SMB_PRODUCER_USER", "SMB_PRODUCER_PASSWORD", "SMB_PRODUCER_DOMAIN");

        private final String usernameVariable;
        private final String passwordVariable;
        private final String domainVariable;

        CredentialProfile(
                String usernameVariable,
                String passwordVariable,
                String domainVariable) {
            this.usernameVariable = usernameVariable;
            this.passwordVariable = passwordVariable;
            this.domainVariable = domainVariable;
        }

        private boolean isComplete(Map<String, String> environment) {
            return hasText(environment.get(usernameVariable))
                    && hasText(environment.get(passwordVariable));
        }

        private boolean isPartiallyConfigured(Map<String, String> environment) {
            return hasText(environment.get(usernameVariable))
                    || hasText(environment.get(passwordVariable));
        }

        private String username(Map<String, String> environment) {
            return environment.get(usernameVariable);
        }

        private String password(Map<String, String> environment) {
            return environment.get(passwordVariable);
        }

        private String domain(Map<String, String> environment) {
            return environment.getOrDefault(domainVariable, "");
        }

        private String requiredPair() {
            return usernameVariable + "/" + passwordVariable;
        }

        private static boolean hasText(String value) {
            return value != null && !value.isBlank();
        }
    }

    record HeldWriteHandle(com.hierynomus.smbj.share.File file)
            implements AutoCloseable {

        @Override
        public void close() {
            file.close();
        }
    }
}
