package com.iocextractor.adapter.in.ingest;

import com.iocextractor.application.tck.junit.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.integration.context.IntegrationContextUtils;
import org.springframework.integration.file.inbound.FileReadingMessageSource;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@IntegrationTest
class IngestFileListFilterIT {

    @TempDir
    Path tempDir;

    @Test
    void accepts_only_included_non_excluded_files_after_quiet_period() throws Exception {
        var clock = Clock.fixed(Instant.parse("2026-06-22T00:00:10Z"), ZoneOffset.UTC);
        var accepted = Files.writeString(tempDir.resolve("source.html"), "data");
        var fresh = Files.writeString(tempDir.resolve("fresh.html"), "data");
        var excluded = Files.writeString(tempDir.resolve("ignored.tmp"), "data");
        Files.setLastModifiedTime(accepted, java.nio.file.attribute.FileTime.from(Instant.parse("2026-06-22T00:00:00Z")));
        Files.setLastModifiedTime(fresh, java.nio.file.attribute.FileTime.from(Instant.parse("2026-06-22T00:00:08Z")));
        Files.setLastModifiedTime(excluded, java.nio.file.attribute.FileTime.from(Instant.parse("2026-06-22T00:00:00Z")));

        var filter = new IngestFileListFilter(List.of("*.html"), List.of("*.tmp"),
                Duration.ofSeconds(5), clock);

        assertThat(filter.filterFiles(tempDir.toFile().listFiles()))
                .containsExactly(accepted.toFile());
    }

    @Test
    void retries_only_matching_files_rejected_during_quiet_period() throws Exception {
        var clock = Clock.fixed(Instant.parse("2026-06-22T00:00:10Z"), ZoneOffset.UTC);
        var fresh = Files.writeString(tempDir.resolve("fresh.html"), "data");
        var excluded = Files.writeString(tempDir.resolve("ignored.tmp"), "data");
        var unsupported = Files.writeString(tempDir.resolve("ignored.txt"), "data");
        Files.setLastModifiedTime(fresh, java.nio.file.attribute.FileTime.from(Instant.parse("2026-06-22T00:00:08Z")));
        Files.setLastModifiedTime(excluded,
                java.nio.file.attribute.FileTime.from(Instant.parse("2026-06-22T00:00:08Z")));
        Files.setLastModifiedTime(unsupported,
                java.nio.file.attribute.FileTime.from(Instant.parse("2026-06-22T00:00:08Z")));
        var retried = new ArrayList<File>();
        var filter = new IngestFileListFilter(List.of("*.html"), List.of("*.tmp"),
                Duration.ofSeconds(5), clock);
        filter.addDiscardCallback(retried::add);

        var accepted = filter.filterFiles(tempDir.toFile().listFiles());

        assertThat(accepted).isEmpty();
        assertThat(retried).containsExactly(fresh.toFile());
    }

    @Test
    void watch_service_retries_matching_file_after_quiet_period() throws Exception {
        var clock = new MutableClock(Instant.parse("2026-06-22T00:00:00Z"));
        var retried = new ArrayList<File>();
        var filter = new IngestFileListFilter(List.of("*.html"), List.of(),
                Duration.ofSeconds(5), clock);
        filter.addDiscardCallback(retried::add);
        var source = new FileReadingMessageSource();
        source.setBeanName("watchServiceQuietPeriodTestSource");
        var beanFactory = new DefaultListableBeanFactory();
        beanFactory.registerSingleton(
                IntegrationContextUtils.INTEGRATION_EVALUATION_CONTEXT_BEAN_NAME,
                new StandardEvaluationContext());
        source.setBeanFactory(beanFactory);
        source.setDirectory(tempDir.toFile());
        source.setUseWatchService(true);
        source.setFilter(filter);
        source.afterPropertiesSet();
        source.start();

        try {
            var candidate = Files.writeString(tempDir.resolve("source.html"), "data");
            Files.setLastModifiedTime(candidate,
                    java.nio.file.attribute.FileTime.from(Instant.parse("2026-06-22T00:00:00Z")));

            awaitWatchRejection(source, retried, candidate.toFile());
            clock.advance(Duration.ofSeconds(6));

            assertThat(source.receive())
                    .isNotNull()
                    .extracting(message -> message.getPayload())
                    .isEqualTo(candidate.toFile());
        } finally {
            source.stop();
            source.destroy();
        }
    }

    private void awaitWatchRejection(FileReadingMessageSource source, List<File> retried, File expected)
            throws InterruptedException {
        for (int attempt = 0; attempt < 100 && !retried.contains(expected); attempt++) {
            source.receive();
            Thread.sleep(10);
        }
        assertThat(retried)
                .as("WatchService event should reach the stability filter before the clock advances")
                .contains(expected);
    }

    private static final class MutableClock extends Clock {

        private final AtomicReference<Instant> instant;

        private MutableClock(Instant instant) {
            this.instant = new AtomicReference<>(instant);
        }

        private void advance(Duration duration) {
            instant.updateAndGet(current -> current.plus(duration));
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return zone.equals(ZoneOffset.UTC) ? this : Clock.fixed(instant(), zone);
        }

        @Override
        public Instant instant() {
            return instant.get();
        }
    }
}
