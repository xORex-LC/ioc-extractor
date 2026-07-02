package com.iocextractor.adapter.out.transport.smb;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class SmbjShareClientFactoryTest {

    @Test
    void separatesConnectRequestAndSocketReaderTimeouts() {
        SmbEndpointSettings settings = new SmbEndpointSettings(
                "primary", "files.example.test", "share", "", "user",
                "secret".toCharArray(), true,
                Duration.ofSeconds(7), Duration.ofSeconds(31), Duration.ofMinutes(5));

        var config = SmbjShareClientFactory.config(settings);

        assertThat(config.getSocketFactory()).isInstanceOf(ConnectTimeoutSocketFactory.class);
        assertThat(config.getSoTimeout()).isZero();
        assertThat(config.getReadTimeout()).isEqualTo(31_000L);
        assertThat(config.getWriteTimeout()).isEqualTo(31_000L);
        assertThat(config.getTransactTimeout()).isEqualTo(31_000L);
    }
}
