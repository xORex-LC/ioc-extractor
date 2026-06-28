package com.iocextractor.application.sync;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SyncValueObjectsTest {

    @Test
    void remoteObjectIdentityUsesPathSizeAndMtime() {
        Instant modifiedAt = Instant.parse("2026-06-28T00:00:00Z");
        RemoteObject object = new RemoteObject("/incoming/a.htm", 42, modifiedAt);

        assertThat(object.identity())
                .isEqualTo(new RemoteObjectIdentity("/incoming/a.htm", 42, modifiedAt));
    }

    @Test
    void publishRequestRejectsUnsafeMarkerNames() {
        assertThatThrownBy(() -> new PublishAtomicallyRequest(
                "dist", "/out", Path.of("slice"), "../_SUCCESS"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("safe path segment");
    }

    @Test
    void retryPolicyRejectsInvalidNumbers() {
        assertThatThrownBy(() -> new RetryPolicy(0, Duration.ofSeconds(1), 2.0d,
                Duration.ofSeconds(5), false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxAttempts");
        assertThatThrownBy(() -> new RetryPolicy(1, Duration.ofSeconds(2), 0.5d,
                Duration.ofSeconds(5), false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("multiplier");
        assertThatThrownBy(() -> new RetryPolicy(1, Duration.ofSeconds(5), 1.0d,
                Duration.ofSeconds(1), false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxBackoff");
    }
}
