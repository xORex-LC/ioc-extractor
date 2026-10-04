package com.iocextractor.application.artifact.lifecycle;

import com.iocextractor.application.port.out.artifact.ArtifactProjectionCommand;
import com.iocextractor.application.port.out.artifact.ArtifactProjectionResult;
import com.iocextractor.application.port.out.artifact.lifecycle.ArtifactProjectionWorkStore;
import com.iocextractor.diagnostics.Diagnostic;
import com.iocextractor.diagnostics.codes.SinkDiagnosticCodes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Clock;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GenerationOwnedArtifactProjectionTest {
    @Test
    void newer_snapshot_satisfies_queued_requests_and_keeps_warning_accounting_and_correlation() {
        var work = new Work(2);
        var builds = new AtomicInteger();
        var warning = Diagnostic.builder(SinkDiagnosticCodes.CHARSET_UNMAPPABLE, Clock.systemUTC())
                .with("runId", "first").with("affectedRows", 3).build();
        var owner = new GenerationOwnedArtifactProjection(List.of("masks"), request -> {
            builds.incrementAndGet();
            work.required = 5;
            return new ArtifactProjectionResult(10, List.of(warning), new ProjectionGeneration(5));
        }, work);

        var first = owner.project(command("first"));
        var reused = owner.project(command("recovery"));

        assertThat(first.installedGeneration()).isEqualTo(new ProjectionGeneration(5));
        assertThat(reused.projectedRows()).isEqualTo(10);
        assertThat(builds).hasValue(1);
        assertThat(work.projected).isEqualTo(5);
        assertThat(reused.diagnostics()).singleElement().satisfies(diagnostic -> {
            assertThat(diagnostic.context()).containsEntry("runId", "recovery").containsEntry("affectedRows", 3);
            assertThat(diagnostic.timestamp()).isEqualTo(warning.timestamp());
        });
        assertThat(warning.context()).containsEntry("runId", "first");
    }

    @Test
    void disabled_lifecycle_always_rebuilds_and_never_acknowledges_untracked_output() {
        var work = new Work(0);
        var builds = new AtomicInteger();
        var owner = new GenerationOwnedArtifactProjection(List.of("masks"), request ->
                ArtifactProjectionResult.clean(builds.incrementAndGet()), work);

        assertThat(owner.project(command("first")).projectedRows()).isOne();
        assertThat(owner.project(command("second")).projectedRows()).isEqualTo(2);
        assertThat(work.acknowledgements).isZero();
    }

    @Test
    void failed_acknowledgement_is_retryable_and_cannot_populate_the_reuse_cache() {
        var work = new Work(1);
        work.acceptAcknowledgement = false;
        var builds = new AtomicInteger();
        var owner = new GenerationOwnedArtifactProjection(List.of("masks"), request -> {
            builds.incrementAndGet();
            return ArtifactProjectionResult.clean(2, new ProjectionGeneration(1));
        }, work);

        assertThatThrownBy(() -> owner.project(command("failed")))
                .hasMessage("Installed projection coverage was not acknowledged");
        assertThat(work.projected).isZero();
        assertThat(work.failureCode).isEqualTo(ArtifactProjectionConvergenceService.PROJECTION_FAILURE);
        work.acceptAcknowledgement = true;
        owner.project(command("retry"));
        assertThat(builds).hasValue(2);
        assertThat(work.projected).isOne();
    }

    @Test
    void cancellation_after_install_leaves_work_pending_and_preserves_interruption() {
        var work = new Work(1);
        var owner = new GenerationOwnedArtifactProjection(List.of("masks"), request -> {
            Thread.currentThread().interrupt();
            return ArtifactProjectionResult.clean(1, new ProjectionGeneration(1));
        }, work);
        try {
            assertThatThrownBy(() -> owner.project(command("cancelled")))
                    .hasMessage("Artifact projection cancelled").hasCauseInstanceOf(InterruptedException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            assertThat(work.projected).isZero();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    @Timeout(15)
    void cancelled_waiter_terminates_while_another_artifact_can_progress() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var waiting = new CountDownLatch(1);
        var waiterStopped = new CountDownLatch(1);
        var work = new Work(0);
        var builds = new AtomicInteger();
        var owner = new GenerationOwnedArtifactProjection(List.of("masks", "hashes"), request -> {
            if (request.artifactName().equals("masks")) {
                builds.incrementAndGet();
                entered.countDown();
                await(release);
            }
            return ArtifactProjectionResult.clean(1);
        }, work);
        var executor = Executors.newFixedThreadPool(3);
        try {
            var first = executor.submit(() -> owner.project(command("first")));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            var waiter = executor.submit(() -> {
                waiting.countDown();
                try {
                    owner.project(command("waiter"));
                } finally {
                    waiterStopped.countDown();
                }
            });
            assertThat(waiting.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(executor.submit(() -> owner.project(new ArtifactProjectionCommand("other", "hashes")))
                    .get(5, TimeUnit.SECONDS).projectedRows()).isOne();
            waiter.cancel(true);
            assertThat(waiterStopped.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(builds).hasValue(1);
            release.countDown();
            first.get(5, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void rejects_ambiguous_catalog_unknown_artifact_and_uncovered_request() {
        var work = new Work(2);
        var installer = (com.iocextractor.application.port.out.artifact.ArtifactProjection)
                request -> ArtifactProjectionResult.clean(0, new ProjectionGeneration(1));
        assertThatThrownBy(() -> new GenerationOwnedArtifactProjection(List.of("masks", "masks"), installer, work))
                .isInstanceOf(IllegalArgumentException.class);
        var owner = new GenerationOwnedArtifactProjection(List.of("masks"), installer, work);
        assertThatThrownBy(() -> owner.project(new ArtifactProjectionCommand("run", "unknown")))
                .hasMessageContaining("Unknown projection artifact");
        assertThatThrownBy(() -> owner.project(command("uncovered")))
                .hasMessage("Installed projection does not cover requested generation");
        assertThat(work.projected).isZero();
    }

    private static ArtifactProjectionCommand command(String run) {
        return new ArtifactProjectionCommand(run, "masks");
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("test interrupted", failure);
        }
    }

    private static final class Work implements ArtifactProjectionWorkStore {
        private long required;
        private long projected;
        private int acknowledgements;
        private boolean acceptAcknowledgement = true;
        private String failureCode;

        private Work(long required) { this.required = required; }

        @Override public ArtifactProjectionState load(String artifact) {
            return new ArtifactProjectionState(artifact, new ProjectionGeneration(required),
                    new ProjectionGeneration(projected));
        }
        @Override public boolean acknowledge(ProjectionAcknowledgement acknowledgement) {
            acknowledgements++;
            if (acceptAcknowledgement) {
                projected = acknowledgement.installedGeneration().value();
            }
            return acceptAcknowledgement;
        }
        @Override public boolean recordFailure(String artifact, ProjectionGeneration generation, String code) {
            failureCode = code;
            return true;
        }
    }
}
