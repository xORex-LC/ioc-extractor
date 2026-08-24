package com.iocextractor.adapter.out.store.jdbc;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

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

    @Test
    void admitsImportIngestLifecycleAndExportSlotWritersInObservedQueueOrder() throws Exception {
        JdbcWriterAdmission admission = new JdbcWriterAdmission();
        CountDownLatch holderEntered = new CountDownLatch(1);
        CountDownLatch releaseHolder = new CountDownLatch(1);
        List<String> admitted = Collections.synchronizedList(new ArrayList<>());
        List<String> families = List.of("import", "ingest", "lifecycle", "export-slot");

        try (var executor = Executors.newFixedThreadPool(families.size() + 1)) {
            Future<?> holder = executor.submit(() -> admission.execute(() -> {
                holderEntered.countDown();
                await(releaseHolder);
                return null;
            }));
            assertThat(holderEntered.await(5, TimeUnit.SECONDS)).isTrue();

            List<Future<?>> waiters = new ArrayList<>();
            for (int index = 0; index < families.size(); index++) {
                String family = families.get(index);
                waiters.add(executor.submit(() -> admission.execute(() -> {
                    admitted.add(family);
                    return null;
                })));
                awaitQueueDepth(admission, index + 1);
            }

            releaseHolder.countDown();
            holder.get(5, TimeUnit.SECONDS);
            for (Future<?> waiter : waiters) {
                waiter.get(5, TimeUnit.SECONDS);
            }
        }

        assertThat(admission.fair()).isTrue();
        assertThat(admitted).containsExactlyElementsOf(families);
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

    private void awaitQueueDepth(JdbcWriterAdmission admission, int expected) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (admission.queuedWriters() < expected && System.nanoTime() < deadline) {
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
        }
        assertThat(admission.queuedWriters()).isGreaterThanOrEqualTo(expected);
    }
}
