package com.iocextractor.bootstrap;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Verifies that the opt-in measurement probe cannot publish partial memory samples. */
class ProcessingRouteComparisonTest {
    @Test
    @Timeout(5)
    void samplerFailureReachesCaller() throws InterruptedException {
        var sampled = new CountDownLatch(1);
        var sampler = new ProcessingRouteComparison.PeakSampler(() -> {
            sampled.countDown();
            throw new IllegalStateException("synthetic status failure");
        });

        assertThat(sampled.await(2, TimeUnit.SECONDS)).isTrue();
        assertThatThrownBy(sampler::close)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Memory sampler failed")
                .hasRootCauseMessage("synthetic status failure");
    }

    @Test
    @Timeout(5)
    void samplerInterruptionReachesCaller() throws InterruptedException {
        var sampled = new CountDownLatch(1);
        var sampler = new ProcessingRouteComparison.PeakSampler(() -> {
            Thread.currentThread().interrupt();
            sampled.countDown();
            return 42;
        });

        assertThat(sampled.await(2, TimeUnit.SECONDS)).isTrue();
        assertThatThrownBy(sampler::close)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Memory sampler failed")
                .hasRootCauseInstanceOf(InterruptedException.class);
    }

    @Test
    @Timeout(5)
    void completedSamplerReturnsItsPeak() throws InterruptedException {
        var sampled = new CountDownLatch(1);
        var sampler = new ProcessingRouteComparison.PeakSampler(() -> {
            sampled.countDown();
            return 42;
        });

        assertThat(sampled.await(2, TimeUnit.SECONDS)).isTrue();
        assertThatNoException().isThrownBy(sampler::close);
    }
}
