package com.iocextractor.adapter.in.ingest;

import com.iocextractor.application.ingest.IngestionStatus;
import com.iocextractor.application.port.in.ingest.IngestSourceResult;
import com.iocextractor.diagnostics.sink.NoopDiagnosticSink;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Executable characterization of the ING-10 startup race. The assertion pins
 * the unsafe pre-fix behavior and is replaced by the desired lifecycle contract
 * when the startup coordinator is introduced.
 */
class IngestionStartupRaceCharacterizationTest {

    @TempDir
    Path tempDir;

    @Test
    void currentWiringAllowsPollerIntakeWhileStartupRecoveryIsStillRunning() throws Exception {
        var recoveryEntered = new CountDownLatch(1);
        var releaseRecovery = new CountDownLatch(1);
        var intakeEntered = new CountDownLatch(1);
        Path source = Files.writeString(tempDir.resolve("startup-source.txt"), "example.com");

        var recoveryRunner = new IngestionRecoveryRunner(() -> {
            recoveryEntered.countDown();
            await(releaseRecovery);
            return List.of();
        });
        var handler = new FileSourceMessageHandler(
                new FileSourceHasher(),
                command -> {
                    intakeEntered.countDown();
                    return new IngestSourceResult(
                            command.key(), IngestionStatus.SOURCE_ARCHIVED, false, null);
                },
                (key, reason) -> {
                    throw new AssertionError("successful intake must not be rejected");
                },
                Clock.systemUTC(),
                1,
                Duration.ZERO,
                NoopDiagnosticSink.INSTANCE);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var recovery = executor.submit(() -> recoveryRunner.run(null));
            assertThat(recoveryEntered.await(5, TimeUnit.SECONDS))
                    .as("startup recovery entered its critical interval")
                    .isTrue();

            var intake = executor.submit(() -> handler.handle(source.toFile()));

            assertThat(intakeEntered.await(5, TimeUnit.SECONDS))
                    .as("the independent poller path enters while recovery is blocked")
                    .isTrue();
            assertThat(recovery.isDone())
                    .as("recovery is still incomplete when intake starts")
                    .isFalse();

            releaseRecovery.countDown();
            recovery.get(5, TimeUnit.SECONDS);
            intake.get(5, TimeUnit.SECONDS);
        } finally {
            releaseRecovery.countDown();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("timed out waiting for test coordination");
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError("test coordination interrupted", failure);
        }
    }
}
