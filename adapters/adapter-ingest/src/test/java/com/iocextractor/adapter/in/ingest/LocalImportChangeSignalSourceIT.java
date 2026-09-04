package com.iocextractor.adapter.in.ingest;

import com.iocextractor.application.dataframeimport.model.ImportSourceId;
import com.iocextractor.application.tck.junit.IntegrationTest;
import com.iocextractor.common.IocExtractorException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@IntegrationTest
class LocalImportChangeSignalSourceIT {

    @TempDir
    Path tempDir;

    @Test
    void emitsDoorbellsAndKeepsWatchingWhenConsumerFails() throws Exception {
        ImportSourceId sourceId = new ImportSourceId("local-a");
        LocalImportChangeSignalSource source = new LocalImportChangeSignalSource(List.of(
                new LocalImportSourceDefinition(sourceId, tempDir)));
        AtomicInteger signals = new AtomicInteger();
        CountDownLatch first = new CountDownLatch(1);
        CountDownLatch second = new CountDownLatch(1);

        try {
            source.start(actual -> {
                assertThat(actual).isEqualTo(sourceId);
                int signal = signals.incrementAndGet();
                if (signal == 1) {
                    first.countDown();
                    throw new IllegalStateException("consumer failure must not stop the doorbell");
                }
                second.countDown();
            });
            source.start(ignored -> signals.addAndGet(100));

            Files.writeString(tempDir.resolve("first.csv"), "first");
            assertThat(first.await(3, TimeUnit.SECONDS))
                    .as("first local import change signal")
                    .isTrue();

            Files.writeString(tempDir.resolve("second.csv"), "second");
            assertThat(second.await(3, TimeUnit.SECONDS))
                    .as("watch thread survives a consumer failure")
                    .isTrue();
            assertThat(signals).hasValueGreaterThanOrEqualTo(2).hasValueLessThan(100);
        } finally {
            source.close();
            source.close();
        }
    }

    @Test
    void rejectsDuplicateNormalizedInboxPaths() {
        Path alias = tempDir.resolve("nested").resolve("..");

        assertThatThrownBy(() -> new LocalImportChangeSignalSource(List.of(
                new LocalImportSourceDefinition(new ImportSourceId("local-a"), tempDir),
                new LocalImportSourceDefinition(new ImportSourceId("local-b"), alias))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Duplicate local import watch path");
    }

    @Test
    void failsClosedWhenInboxCannotBeRegisteredAndAllowsNoopLifecycle() {
        LocalImportChangeSignalSource empty = new LocalImportChangeSignalSource(List.of());
        empty.close();

        LocalImportChangeSignalSource missing = new LocalImportChangeSignalSource(List.of(
                new LocalImportSourceDefinition(
                        new ImportSourceId("missing"), tempDir.resolve("does-not-exist"))));

        assertThatThrownBy(() -> missing.start(ignored -> { }))
                .isInstanceOf(IocExtractorException.class)
                .hasMessage("Failed to start local import watch hints")
                .hasRootCauseInstanceOf(java.io.IOException.class);
        org.assertj.core.api.Assertions.assertThatCode(missing::close)
                .doesNotThrowAnyException();
    }
}
