package com.iocextractor.adapter.out.transport.smb;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SmbEndpointSettingsTest {

    @Test
    void toStringDoesNotExposePassword() {
        SmbEndpointSettings settings = endpoint("secret-password".toCharArray());

        assertThat(settings.toString())
                .contains("password=<redacted>")
                .doesNotContain("secret-password");
    }

    @Test
    void passwordIsDefensivelyCopied() {
        char[] original = "secret".toCharArray();
        SmbEndpointSettings settings = endpoint(original);

        original[0] = 'X';
        char[] exposed = settings.password();
        exposed[1] = 'Y';

        assertThat(settings.password()).containsExactly('s', 'e', 'c', 'r', 'e', 't');
    }

    @Test
    void defaultsToStandardSmbPort() {
        assertThat(endpoint("secret".toCharArray()).port())
                .isEqualTo(SmbEndpointSettings.DEFAULT_PORT);
    }

    @Test
    void acceptsCustomSmbPort() {
        assertThat(endpoint("secret".toCharArray(), 1_445).port()).isEqualTo(1_445);
    }

    @Test
    void rejectsPortsOutsideTcpRange() {
        assertThatThrownBy(() -> endpoint("secret".toCharArray(), 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("port");
        assertThatThrownBy(() -> endpoint("secret".toCharArray(), 65_536))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("port");
    }

    private static SmbEndpointSettings endpoint(char[] password) {
        return new SmbEndpointSettings(
                "primary",
                "files.example.test",
                "export",
                "DOMAIN",
                "sync-user",
                password,
                SmbEncryptionPolicy.REQUIRED,
                Duration.ofSeconds(5),
                Duration.ofSeconds(30),
                Duration.ofMinutes(2));
    }

    private static SmbEndpointSettings endpoint(char[] password, int port) {
        return new SmbEndpointSettings(
                "primary",
                "files.example.test",
                port,
                "export",
                "DOMAIN",
                "sync-user",
                password,
                SmbEncryptionPolicy.REQUIRED,
                Duration.ofSeconds(5),
                Duration.ofSeconds(30),
                Duration.ofMinutes(2));
    }
}
