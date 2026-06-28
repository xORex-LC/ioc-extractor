package com.iocextractor.application.sync;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RetrierTest {

    @Test
    void retriesRetryNowFailuresUntilSuccess() {
        List<Duration> sleeps = new ArrayList<>();
        Retrier retrier = new Retrier(policy(3), sleeps::add);
        AtomicInteger attempts = new AtomicInteger();

        String result = retrier.execute(() -> {
            if (attempts.incrementAndGet() < 3) {
                throw new RemoteTransportException(RemoteErrorKind.TRANSIENT, "temporary");
            }
            return "ok";
        });

        assertThat(result).isEqualTo("ok");
        assertThat(attempts).hasValue(3);
        assertThat(sleeps).containsExactly(Duration.ofSeconds(1), Duration.ofSeconds(2));
    }

    @Test
    void stopsAtMaxAttempts() {
        Retrier retrier = new Retrier(policy(2), ignored -> { });
        AtomicInteger attempts = new AtomicInteger();

        assertThatThrownBy(() -> retrier.execute(() -> {
            attempts.incrementAndGet();
            throw new RemoteTransportException(RemoteErrorKind.TRANSIENT, "still broken");
        }))
                .isInstanceOf(RemoteTransportException.class)
                .hasMessageContaining("still broken");
        assertThat(attempts).hasValue(2);
    }

    @Test
    void doesNotRetryMacroOrFatalFailures() {
        Retrier retrier = new Retrier(policy(3), ignored -> { });
        AtomicInteger attempts = new AtomicInteger();

        assertThatThrownBy(() -> retrier.execute(() -> {
            attempts.incrementAndGet();
            throw new RemoteTransportException(RemoteErrorKind.UNREACHABLE, "network");
        }))
                .isInstanceOf(RemoteTransportException.class)
                .hasMessageContaining("network");
        assertThat(attempts).hasValue(1);
    }

    @Test
    void restoresInterruptFlagWhenSleepIsInterrupted() {
        Retrier retrier = new Retrier(policy(3), ignored -> {
            throw new InterruptedException("stop");
        });

        assertThatThrownBy(() -> retrier.execute(() -> {
            throw new RemoteTransportException(RemoteErrorKind.TRANSIENT, "temporary");
        }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Interrupted");
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
        Thread.interrupted();
    }

    private RetryPolicy policy(int maxAttempts) {
        return new RetryPolicy(maxAttempts, Duration.ofSeconds(1), 2.0d, Duration.ofSeconds(5), false);
    }
}
