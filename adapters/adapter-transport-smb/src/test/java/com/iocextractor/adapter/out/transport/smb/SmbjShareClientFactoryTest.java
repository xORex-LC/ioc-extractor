package com.iocextractor.adapter.out.transport.smb;

import com.hierynomus.smbj.SMBClient;
import com.hierynomus.smbj.connection.Connection;
import com.iocextractor.application.sync.RemoteErrorDisposition;
import com.iocextractor.application.sync.RemoteErrorKind;
import com.iocextractor.application.sync.RemoteTransportException;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;

import static com.hierynomus.mssmb2.SMB2Dialect.SMB_2_1;
import static com.hierynomus.mssmb2.SMB2Dialect.SMB_3_0;
import static com.hierynomus.mssmb2.SMB2Dialect.SMB_3_0_2;
import static com.hierynomus.mssmb2.SMB2Dialect.SMB_3_1_1;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SmbjShareClientFactoryTest {

    @Test
    void separatesConnectRequestAndSocketReaderTimeouts() {
        SmbEndpointSettings settings = settings();

        var config = SmbjShareClientFactory.config(settings);

        assertThat(config.getSocketFactory()).isInstanceOf(ConnectTimeoutSocketFactory.class);
        assertThat(config.getSoTimeout()).isZero();
        assertThat(config.getReadTimeout()).isEqualTo(31_000L);
        assertThat(config.getWriteTimeout()).isEqualTo(31_000L);
        assertThat(config.getTransactTimeout()).isEqualTo(31_000L);
    }

    @Test
    void connectsToConfiguredHostAndPort() throws IOException {
        try (RecordingSmbClient client = new RecordingSmbClient()) {
            SmbjShareClientFactory.connect(client, settings(1_445));

            assertThat(client.host).isEqualTo("files.example.test");
            assertThat(client.port).isEqualTo(1_445);
        }
    }

    @Test
    void requiredEncryptionAdvertisesEncryptionAndAllowsOnlySmb3() {
        var config = SmbjShareClientFactory.config(settings(SmbEncryptionPolicy.REQUIRED));

        assertThat(config.isEncryptData()).isTrue();
        assertThat(config.getSupportedDialects())
                .containsExactlyInAnyOrder(SMB_3_1_1, SMB_3_0_2, SMB_3_0);
    }

    @Test
    void preferredEncryptionAllowsProtocolFallback() {
        var config = SmbjShareClientFactory.config(settings(SmbEncryptionPolicy.PREFERRED));

        assertThat(config.isEncryptData()).isTrue();
        assertThat(config.getSupportedDialects()).contains(SMB_3_1_1, SMB_2_1);
    }

    @Test
    void disabledEncryptionDoesNotAdvertiseClientPreference() {
        var config = SmbjShareClientFactory.config(settings(SmbEncryptionPolicy.DISABLED));

        assertThat(config.isEncryptData()).isFalse();
    }

    @Test
    void requiredEncryptionRejectsAnUnencryptedNegotiatedSession() {
        SmbEndpointSettings settings = settings(SmbEncryptionPolicy.REQUIRED);

        assertThatThrownBy(() -> SmbjShareClientFactory.requireEncryption(
                settings, SMB_3_1_1, false))
                .isInstanceOfSatisfying(RemoteTransportException.class, failure -> {
                    assertThat(failure.kind()).isEqualTo(RemoteErrorKind.SECURITY_POLICY_UNMET);
                    assertThat(failure.kind().disposition()).isEqualTo(RemoteErrorDisposition.FAIL);
                    assertThat(failure).hasMessageContaining("SMB3 encryption is required");
                });
    }

    @Test
    void fallbackPoliciesAndEffectiveRequiredEncryptionAreAccepted() {
        assertThatNoException().isThrownBy(() -> {
            SmbjShareClientFactory.requireEncryption(
                    settings(SmbEncryptionPolicy.REQUIRED), SMB_3_1_1, true);
            SmbjShareClientFactory.requireEncryption(
                    settings(SmbEncryptionPolicy.PREFERRED), SMB_2_1, false);
            SmbjShareClientFactory.requireEncryption(
                    settings(SmbEncryptionPolicy.DISABLED), SMB_2_1, false);
        });
    }

    @Test
    void rejectsMissingOrNonDiskShareWithoutRetryingConfiguration() {
        assertThatThrownBy(() -> SmbjShareClientFactory.requireDiskShare(null, settings()))
                .isInstanceOfSatisfying(RemoteTransportException.class, failure -> {
                    assertThat(failure.kind()).isEqualTo(RemoteErrorKind.NOT_FOUND);
                    assertThat(failure.kind().disposition()).isEqualTo(RemoteErrorDisposition.FAIL);
                    assertThat(failure).hasMessageContaining("is not a disk share");
                });
    }

    private SmbEndpointSettings settings() {
        return settings(SmbEncryptionPolicy.REQUIRED);
    }

    private SmbEndpointSettings settings(SmbEncryptionPolicy encryption) {
        return new SmbEndpointSettings(
                "primary", "files.example.test", "share", "", "user",
                "secret".toCharArray(), encryption,
                Duration.ofSeconds(7), Duration.ofSeconds(31), Duration.ofMinutes(5));
    }

    private SmbEndpointSettings settings(int port) {
        return new SmbEndpointSettings(
                "primary", "files.example.test", port, "share", "", "user",
                "secret".toCharArray(), SmbEncryptionPolicy.REQUIRED,
                Duration.ofSeconds(7), Duration.ofSeconds(31), Duration.ofMinutes(5));
    }

    private static final class RecordingSmbClient extends SMBClient {
        private String host;
        private int port;

        @Override
        public Connection connect(String host, int port) {
            this.host = host;
            this.port = port;
            return null;
        }
    }
}
