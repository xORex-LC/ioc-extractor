package com.iocextractor.adapter.out.transport.smb;

import com.iocextractor.application.port.out.sync.RemoteChangeSignalHandler;
import com.iocextractor.application.port.out.sync.RemoteChangeWatch;
import com.iocextractor.application.sync.RemoteFetchSource;
import com.iocextractor.application.sync.RetryPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfSystemProperty(named = "ioc.smb.contract", matches = "true")
class SmbChangeNotifyContractTest {

    @TempDir
    Path tempDir;

    @Test
    void watcherSignalsWhenFileIsCreatedOnLiveShare() throws Exception {
        SmbEndpointSettings settings = settings();
        String remotePath = require("ioc.smb.remotePath");
        RemoteFetchSource source = new RemoteFetchSource("contract", settings.name(), remotePath, List.of("*"), List.of());
        RecordingHandler handler = new RecordingHandler();
        SmbChangeNotifyWatcher watcher = new SmbChangeNotifyWatcher(
                List.of(settings),
                new RetryPolicy(3, Duration.ofMillis(100), 1.0d, Duration.ofMillis(100), false));
        String fileName = "codex-change-notify-" + UUID.randomUUID() + ".txt";
        Path localFile = tempDir.resolve(fileName);
        Files.writeString(localFile, "contract\n");
        String remoteFile = SmbFileTransport.join(remotePath, fileName);

        try (RemoteChangeWatch watch = watcher.watch(source, handler);
             SmbShareClient client = new SmbjShareClientFactory().open(settings)) {
            waitUntil(() -> handler.established.get() > 0);
            client.upload(localFile, remoteFile);

            waitUntil(() -> handler.signals.get() > 0);
        } finally {
            try (SmbShareClient client = new SmbjShareClientFactory().open(settings)) {
                client.delete(remoteFile);
            }
        }

        assertThat(handler.failures).hasValue(0);
    }

    @Test
    void watcherSurvivesIdleLongerThanRequestTimeoutBeforeSignal() throws Exception {
        SmbEndpointSettings settings = settings(Duration.ofSeconds(2));
        String remotePath = require("ioc.smb.remotePath");
        RemoteFetchSource source = new RemoteFetchSource("contract", settings.name(), remotePath, List.of("*"), List.of());
        RecordingHandler handler = new RecordingHandler();
        SmbChangeNotifyWatcher watcher = new SmbChangeNotifyWatcher(
                List.of(settings),
                new RetryPolicy(3, Duration.ofMillis(100), 1.0d, Duration.ofMillis(100), false));
        String fileName = "codex-change-notify-idle-" + UUID.randomUUID() + ".txt";
        Path localFile = tempDir.resolve(fileName);
        Files.writeString(localFile, "contract\n");
        String remoteFile = SmbFileTransport.join(remotePath, fileName);
        boolean uploaded = false;

        try (RemoteChangeWatch watch = watcher.watch(source, handler);
             SmbShareClient client = new SmbjShareClientFactory().open(settings)) {
            waitUntil(() -> handler.established.get() > 0);
            Thread.sleep(Duration.ofSeconds(3));

            assertThat(handler.established).hasValue(1);
            assertThat(handler.failures).hasValue(0);
            assertThat(handler.signals).hasValue(0);

            client.upload(localFile, remoteFile);
            uploaded = true;

            waitUntil(() -> handler.signals.get() > 0);
        } finally {
            if (uploaded) {
                try (SmbShareClient client = new SmbjShareClientFactory().open(settings)) {
                    client.delete(remoteFile);
                }
            }
        }

        assertThat(handler.failures).hasValue(0);
    }

    private static SmbEndpointSettings settings() {
        return settings(Duration.ofSeconds(30));
    }

    private static SmbEndpointSettings settings(Duration requestTimeout) {
        char[] password = require("ioc.smb.password").toCharArray();
        try {
            return new SmbEndpointSettings(
                    "contract",
                    require("ioc.smb.host"),
                    require("ioc.smb.share"),
                    System.getProperty("ioc.smb.domain", ""),
                    require("ioc.smb.username"),
                    password,
                    Boolean.getBoolean("ioc.smb.encrypt"),
                    Duration.ofSeconds(5),
                    requestTimeout,
                    Duration.ofMinutes(1));
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    private static String require(String property) {
        String value = System.getProperty(property);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing required system property: " + property);
        }
        return value;
    }

    private static void waitUntil(BooleanCondition condition) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (condition.matches()) {
                return;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            }
        }
        assertThat(condition.matches()).isTrue();
    }

    @FunctionalInterface
    private interface BooleanCondition {
        boolean matches();
    }

    private static final class RecordingHandler implements RemoteChangeSignalHandler {
        private final AtomicInteger established = new AtomicInteger();
        private final AtomicInteger signals = new AtomicInteger();
        private final AtomicInteger failures = new AtomicInteger();

        @Override
        public void signal() {
            signals.incrementAndGet();
        }

        @Override
        public void established() {
            established.incrementAndGet();
        }

        @Override
        public void failed(RuntimeException failure) {
            failures.incrementAndGet();
        }
    }
}
