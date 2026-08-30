package com.iocextractor.adapter.out.transport.smb;

import com.iocextractor.application.sync.RemoteErrorKind;
import com.iocextractor.application.sync.RemoteTransportException;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Optional;
import java.util.Queue;

import static com.iocextractor.adapter.out.transport.smb.SmbTransportTelemetry.Role.POOLED_TRANSPORT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SmbSessionPoolTelemetryTest {

    @Test
    void recordsOpenFailureAndClearsCapacitySignalAfterReconnect() {
        QueueFactory factory = new QueueFactory();
        factory.outcomes.add(new RemoteTransportException(
                RemoteErrorKind.RESOURCE_EXHAUSTED, "session limit reached"));
        factory.outcomes.add(new NoopClient());
        SmbTransportTelemetry telemetry = new SmbTransportTelemetry();
        SmbSessionPool pool = new SmbSessionPool(
                List.of(settings()), factory, Clock.systemUTC(), telemetry);

        assertThatThrownBy(() -> pool.withClient("primary", "list", client -> List.of()))
                .isInstanceOf(RemoteTransportException.class)
                .extracting("kind")
                .isEqualTo(RemoteErrorKind.RESOURCE_EXHAUSTED);
        assertThat(telemetry.snapshot("primary", POOLED_TRANSPORT).resourceConstrained()).isTrue();

        pool.withClient("primary", "list", client -> List.of());

        assertThat(telemetry.snapshot("primary", POOLED_TRANSPORT))
                .extracting(
                        SmbTransportTelemetry.Snapshot::activeSessions,
                        SmbTransportTelemetry.Snapshot::successfulOpens,
                        SmbTransportTelemetry.Snapshot::openFailures,
                        SmbTransportTelemetry.Snapshot::resourceExhaustions,
                        SmbTransportTelemetry.Snapshot::resourceConstrained)
                .containsExactly(1, 1L, 1L, 1L, false);

        pool.close();

        assertThat(telemetry.snapshot("primary", POOLED_TRANSPORT).activeSessions()).isZero();
    }

    private SmbEndpointSettings settings() {
        return new SmbEndpointSettings(
                "primary", "files.example.test", "share", "", "sync-user",
                "secret".toCharArray(), SmbEncryptionPolicy.DISABLED,
                Duration.ofSeconds(1), Duration.ofSeconds(30), Duration.ofMinutes(5));
    }

    private static final class QueueFactory implements SmbShareClientFactory {

        private final Queue<Object> outcomes = new ArrayDeque<>();

        @Override
        public SmbShareClient open(SmbEndpointSettings settings) {
            Object outcome = outcomes.remove();
            if (outcome instanceof RuntimeException failure) {
                throw failure;
            }
            return (SmbShareClient) outcome;
        }
    }

    private static final class NoopClient implements SmbShareClient {

        @Override
        public List<SmbRemoteEntry> list(String remotePath) {
            return List.of();
        }

        @Override
        public Optional<SmbRemoteEntry> stat(String remotePath) {
            return Optional.empty();
        }

        @Override
        public void download(String remotePath, Path localDestination) {
        }

        @Override
        public void delete(String remotePath) {
        }

        @Override
        public void deleteRegularFile(String remotePath) {
        }

        @Override
        public boolean fileExists(String remotePath) {
            return false;
        }

        @Override
        public boolean directoryExists(String remotePath) {
            return false;
        }

        @Override
        public void createDirectories(String remotePath) {
        }

        @Override
        public void createEmptyFile(String remotePath) {
        }

        @Override
        public void upload(Path localFile, String remotePath) {
        }

        @Override
        public String readText(String remotePath) {
            return "";
        }

        @Override
        public void rename(String source, String target) {
        }

        @Override
        public void deleteTree(String remotePath) {
        }

        @Override
        public void close() {
        }
    }
}
