package com.iocextractor.bootstrap;

import com.iocextractor.application.port.in.sync.RemoteFetchResult;
import com.iocextractor.application.sync.RemoteFetchSource;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class DaemonFetchSchedulerTest {

    @Test
    void sourceFailureIsIsolatedAndRetriedOnNextTick() {
        List<String> calls = new ArrayList<>();
        AtomicInteger firstAttempts = new AtomicInteger();
        DaemonFetchScheduler scheduler = scheduler(command -> {
            String source = command.source().orElseThrow();
            calls.add(source);
            if (source.equals("one") && firstAttempts.getAndIncrement() == 0) {
                throw new IllegalStateException("unreachable");
            }
            return new RemoteFetchResult(1, 0, 0);
        });

        scheduler.runOnce();
        scheduler.runOnce();

        assertThat(calls).containsExactly("one", "two", "one", "two");
    }

    @Test
    void slowCycleDoesNotOverlap() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        DaemonFetchScheduler scheduler = new DaemonFetchScheduler(
                List.of(source("one")), command -> {
                    calls.incrementAndGet();
                    entered.countDown();
                    await(release);
                    return new RemoteFetchResult(1, 0, 0);
                }, registry(), healthState(), Duration.ofHours(1));
        Thread first = new Thread(scheduler::runOnce);

        first.start();
        assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
        scheduler.runOnce();
        release.countDown();
        first.join(1000);

        assertThat(calls).hasValue(1);
    }

    @Test
    void lifecycleStartsBeforeExportAndStopsCleanly() {
        DaemonFetchScheduler scheduler = scheduler(command -> new RemoteFetchResult(0, 0, 0));

        scheduler.start();
        assertThat(scheduler.isRunning()).isTrue();
        assertThat(scheduler.getPhase()).isLessThan(DaemonExportScheduler.PHASE);
        scheduler.stop();

        assertThat(scheduler.isRunning()).isFalse();
    }

    @Test
    void publishesLatestSourceResultToHealthState() {
        SyncHealthState state = new SyncHealthState(
                Clock.fixed(Instant.parse("2026-06-28T00:00:00Z"), ZoneOffset.UTC));
        DaemonFetchScheduler scheduler = new DaemonFetchScheduler(
                List.of(source("one")), command -> new RemoteFetchResult(2, 3, 0),
                registry(), state, Duration.ofHours(1));

        scheduler.runOnce();

        assertThat(state.fetchSnapshots().get("one"))
                .extracting(SyncHealthState.FetchSnapshot::fetched,
                        SyncHealthState.FetchSnapshot::skipped,
                        SyncHealthState.FetchSnapshot::failed)
                .containsExactly(2, 3, 0);
    }

    private DaemonFetchScheduler scheduler(
            com.iocextractor.application.port.in.sync.RemoteFetchUseCase useCase) {
        return new DaemonFetchScheduler(
                List.of(source("one"), source("two")), useCase, registry(), healthState(), Duration.ofHours(1));
    }

    private RemoteFetchSource source(String id) {
        return new RemoteFetchSource(id, "endpoint-" + id, "/" + id, List.of("*"), List.of());
    }

    private TransportRegistry registry() {
        return new TransportRegistry(List.of());
    }

    private SyncHealthState healthState() {
        return new SyncHealthState(Clock.systemUTC());
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }
}
