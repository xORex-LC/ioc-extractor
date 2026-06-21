package com.iocextractor.adapter.in.ingest;

import com.iocextractor.application.ingest.SourceKey;
import com.iocextractor.application.port.in.ingest.IngestSourceCommand;
import com.iocextractor.application.port.in.ingest.IngestSourceResult;
import com.iocextractor.application.port.in.ingest.IngestSourceUseCase;
import com.iocextractor.application.port.in.ingest.RejectIngestionUseCase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileSourceMessageHandlerTest {

    @TempDir
    Path tempDir;

    @Test
    void rejects_source_only_after_retries_are_exhausted() throws Exception {
        Path source = Files.writeString(tempDir.resolve("source.html"), "ioc");
        var ingest = new FailingIngestUseCase();
        var reject = new RecordingRejectUseCase();
        var handler = new FileSourceMessageHandler(
                new FileSourceHasher(),
                ingest,
                reject,
                Clock.fixed(Instant.parse("2026-06-22T00:00:00Z"), ZoneOffset.UTC),
                2,
                Duration.ZERO);

        assertThatThrownBy(() -> handler.handle(source.toFile()))
                .hasMessageContaining("Source ingestion failed after retries");

        assertThat(ingest.attempts).isEqualTo(2);
        assertThat(reject.key).isNotNull();
        assertThat(reject.reason).isEqualTo("boom");
    }

    private static final class FailingIngestUseCase implements IngestSourceUseCase {
        private int attempts;

        @Override
        public IngestSourceResult ingest(IngestSourceCommand command) {
            attempts++;
            throw new IllegalStateException("boom");
        }
    }

    private static final class RecordingRejectUseCase implements RejectIngestionUseCase {
        private SourceKey key;
        private String reason;

        @Override
        public void reject(SourceKey key, String reason) {
            this.key = key;
            this.reason = reason;
        }
    }
}
