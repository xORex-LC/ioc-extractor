package com.iocextractor.adapter.out.transport.smb;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

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

    private static SmbEndpointSettings endpoint(char[] password) {
        return new SmbEndpointSettings(
                "primary",
                "files.example.test",
                "export",
                "DOMAIN",
                "sync-user",
                password,
                true,
                Duration.ofSeconds(5),
                Duration.ofSeconds(30),
                Duration.ofMinutes(2));
    }
}
