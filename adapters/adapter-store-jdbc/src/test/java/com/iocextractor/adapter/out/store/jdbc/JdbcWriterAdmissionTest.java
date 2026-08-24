package com.iocextractor.adapter.out.store.jdbc;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcWriterAdmissionTest {

    @Test
    void usesFairInterruptibleAdmissionAndNeverRunsTwoLocalWritersTogether() throws Exception {
        JdbcWriterAdmission admission = new JdbcWriterAdmission();
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maximum = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(3)) {
            var first = executor.submit(() -> admission.execute(() -> {
                int current = active.incrementAndGet();
                maximum.accumulateAndGet(current, Math::max);
                entered.countDown();
                await(release);
                active.decrementAndGet();
                return null;
            }));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            var second = executor.submit(() -> admitted(admission, active, maximum));
            var third = executor.submit(() -> admitted(admission, active, maximum));
            release.countDown();
            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);
            third.get(5, TimeUnit.SECONDS);
        }

        assertThat(admission.fair()).isTrue();
        assertThat(maximum).hasValue(1);
    }

    private Void admitted(JdbcWriterAdmission admission,
                          AtomicInteger active,
                          AtomicInteger maximum) {
        return admission.execute(() -> {
            int current = active.incrementAndGet();
            maximum.accumulateAndGet(current, Math::max);
            active.decrementAndGet();
            return null;
        });
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting for writer test release");
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError(failure);
        }
    }
}
