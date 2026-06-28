package com.iocextractor.bootstrap;

import com.iocextractor.application.port.in.sync.ArtifactPublishCommand;
import com.iocextractor.application.port.in.sync.ArtifactPublishResult;
import com.iocextractor.application.port.in.sync.ArtifactPublishUseCase;
import com.iocextractor.application.sync.PublishTarget;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class DaemonPublishSchedulerTest {

    @Test
    void startReconcilesEveryTargetBeforePeriodicLoop() {
        RecordingPublisher publisher = new RecordingPublisher();
        DaemonPublishScheduler scheduler = scheduler(publisher);

        scheduler.start();
        try {
            assertThat(publisher.reconciled).containsExactly("one", "two");
            assertThat(publisher.published).isEmpty();
            assertThat(scheduler.getPhase()).isGreaterThan(DaemonExportScheduler.PHASE)
                    .isLessThan(DaemonSliceRetentionScheduler.PHASE);
        } finally {
            scheduler.stop();
        }
    }

    @Test
    void targetFailureIsIsolatedAndRetriedOnNextTick() {
        RecordingPublisher publisher = new RecordingPublisher();
        publisher.failFirstTargetOnce = true;
        DaemonPublishScheduler scheduler = scheduler(publisher);

        scheduler.runOnce();
        scheduler.runOnce();

        assertThat(publisher.published).containsExactly("one", "two", "one", "two");
    }

    @Test
    void slowCycleDoesNotOverlap() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger attempts = new AtomicInteger();
        ArtifactPublishUseCase publisher = new ArtifactPublishUseCase() {
            @Override
            public ArtifactPublishResult reconcile(ArtifactPublishCommand command) {
                return empty();
            }

            @Override
            public ArtifactPublishResult publish(ArtifactPublishCommand command) {
                attempts.incrementAndGet();
                entered.countDown();
                await(release);
                return empty();
            }
        };
        DaemonPublishScheduler scheduler = new DaemonPublishScheduler(
                List.of(target("one")), publisher, registry(), Duration.ofHours(1));
        Thread first = new Thread(scheduler::runOnce);

        first.start();
        assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
        scheduler.runOnce();
        release.countDown();
        first.join(1000);

        assertThat(attempts).hasValue(1);
    }

    private DaemonPublishScheduler scheduler(ArtifactPublishUseCase publisher) {
        return new DaemonPublishScheduler(
                List.of(target("one"), target("two")), publisher, registry(), Duration.ofHours(1));
    }

    private PublishTarget target(String id) {
        return new PublishTarget(id, "endpoint-" + id, "/" + id, "profile-" + id);
    }

    private TransportRegistry registry() {
        return new TransportRegistry(List.of());
    }

    private static ArtifactPublishResult empty() {
        return new ArtifactPublishResult(0, 0, 0, 0);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    private static final class RecordingPublisher implements ArtifactPublishUseCase {
        private final List<String> reconciled = new ArrayList<>();
        private final List<String> published = new ArrayList<>();
        private boolean failFirstTargetOnce;

        @Override
        public ArtifactPublishResult reconcile(ArtifactPublishCommand command) {
            reconciled.add(command.target().orElseThrow());
            return new ArtifactPublishResult(1, 0, 0, 0);
        }

        @Override
        public ArtifactPublishResult publish(ArtifactPublishCommand command) {
            String target = command.target().orElseThrow();
            published.add(target);
            if (target.equals("one") && failFirstTargetOnce) {
                failFirstTargetOnce = false;
                throw new IllegalStateException("unreachable");
            }
            return new ArtifactPublishResult(0, 1, 0, 0);
        }
    }
}
