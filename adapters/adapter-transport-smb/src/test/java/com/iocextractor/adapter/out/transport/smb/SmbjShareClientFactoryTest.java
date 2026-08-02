package com.iocextractor.adapter.out.transport.smb;

import com.iocextractor.application.sync.RemoteErrorDisposition;
import com.iocextractor.application.sync.RemoteErrorKind;
import com.iocextractor.application.sync.RemoteTransportException;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
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
    void rejectsMissingOrNonDiskShareWithoutRetryingConfiguration() {
        assertThatThrownBy(() -> SmbjShareClientFactory.requireDiskShare(null, settings()))
                .isInstanceOfSatisfying(RemoteTransportException.class, failure -> {
                    assertThat(failure.kind()).isEqualTo(RemoteErrorKind.NOT_FOUND);
                    assertThat(failure.kind().disposition()).isEqualTo(RemoteErrorDisposition.FAIL);
                    assertThat(failure).hasMessageContaining("is not a disk share");
                });
    }

    private SmbEndpointSettings settings() {
        return new SmbEndpointSettings(
                "primary", "files.example.test", "share", "", "user",
                "secret".toCharArray(), true,
                Duration.ofSeconds(7), Duration.ofSeconds(31), Duration.ofMinutes(5));
    }
}
