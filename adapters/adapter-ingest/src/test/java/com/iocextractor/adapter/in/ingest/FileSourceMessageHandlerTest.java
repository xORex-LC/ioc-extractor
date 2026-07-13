package com.iocextractor.adapter.in.ingest;

import com.iocextractor.application.ingest.SourceKey;
import com.iocextractor.application.ingest.IngestionStatus;
import com.iocextractor.application.port.in.ingest.IngestSourceCommand;
import com.iocextractor.application.port.in.ingest.IngestSourceResult;
import com.iocextractor.application.port.in.ingest.IngestSourceUseCase;
import com.iocextractor.application.port.in.ingest.RejectIngestionUseCase;
import com.iocextractor.diagnostics.sink.CollectingDiagnosticSink;
import com.iocextractor.diagnostics.Diagnostic;
import com.iocextractor.diagnostics.DiagnosticException;
import com.iocextractor.diagnostics.codes.IngestDiagnosticCodes;
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
                Duration.ZERO,
                new CollectingDiagnosticSink());

        assertThatThrownBy(() -> handler.handle(source.toFile()))
                .hasMessageContaining("Source ingestion failed after retries");

        assertThat(ingest.attempts).isEqualTo(2);
        assertThat(reject.key).isNotNull();
        assertThat(reject.reason).isEqualTo("boom");
    }

    @Test
    void emitsTypedIngestDiagnosticOnceAfterRetriesAndRejection() throws Exception {
        Path source = Files.writeString(tempDir.resolve("source.html"), "ioc");
        var diagnostic = Diagnostic.builder(IngestDiagnosticCodes.CLAIM_FAILED,
                        Clock.fixed(Instant.parse("2026-06-22T00:00:00Z"), ZoneOffset.UTC))
                .with("source", source)
                .with("reason", "claim failed")
                .build();
        var diagnostics = new CollectingDiagnosticSink();
        var handler = new FileSourceMessageHandler(
                new FileSourceHasher(),
                command -> {
                    throw new DiagnosticException(diagnostic);
                },
                (key, reason) -> { },
                Clock.systemUTC(),
                3,
                Duration.ZERO,
                diagnostics);

        assertThatThrownBy(() -> handler.handle(source.toFile()))
                .hasMessageContaining("Source ingestion failed after retries");

        assertThat(diagnostics.diagnostics()).containsExactly(diagnostic);
    }

    @Test
    void rejectionDiagnosticReplacesEarlierAttemptDiagnostic() throws Exception {
        Path source = Files.writeString(tempDir.resolve("source.html"), "ioc");
        Clock clock = Clock.fixed(Instant.parse("2026-06-22T00:00:00Z"), ZoneOffset.UTC);
        var claimFailure = Diagnostic.builder(IngestDiagnosticCodes.CLAIM_FAILED, clock)
                .with("source", source)
                .with("reason", "claim failed")
                .build();
        var deadLetterFailure = Diagnostic.builder(IngestDiagnosticCodes.DEAD_LETTER_FAILED, clock)
                .with("source", "ABC123")
                .with("reason", "failed area unavailable")
                .build();
        var diagnostics = new CollectingDiagnosticSink();
        var handler = new FileSourceMessageHandler(
                new FileSourceHasher(),
                command -> {
                    throw new DiagnosticException(claimFailure);
                },
                (key, reason) -> {
                    throw new DiagnosticException(deadLetterFailure);
                },
                clock,
                2,
                Duration.ZERO,
                diagnostics);

        assertThatThrownBy(() -> handler.handle(source.toFile()))
                .hasCauseInstanceOf(DiagnosticException.class);

        assertThat(diagnostics.diagnostics()).containsExactly(deadLetterFailure);
    }

    @Test
    void durableFailedRetryPreservesEarlierTypedFailureWithoutSecondRejection() throws Exception {
        Path source = Files.writeString(tempDir.resolve("source.html"), "ioc");
        Clock clock = Clock.fixed(Instant.parse("2026-06-22T00:00:00Z"), ZoneOffset.UTC);
        var diagnostic = Diagnostic.builder(IngestDiagnosticCodes.LEDGER_WRITE_FAILED, clock)
                .with("source", "ABC123")
                .with("reason", "ledger unavailable")
                .build();
        var diagnostics = new CollectingDiagnosticSink();
        var reject = new RecordingRejectUseCase();
        var ingest = new FailedAfterFirstAttemptUseCase(diagnostic);
        var handler = new FileSourceMessageHandler(
                new FileSourceHasher(), ingest, reject, clock, 3, Duration.ZERO, diagnostics);

        assertThatThrownBy(() -> handler.handle(source.toFile()))
                .hasCauseInstanceOf(DiagnosticException.class);

        assertThat(ingest.attempts).isEqualTo(2);
        assertThat(reject.key).isNull();
        assertThat(diagnostics.diagnostics()).containsExactly(diagnostic);
    }

    private static final class FailingIngestUseCase implements IngestSourceUseCase {
        private int attempts;

        @Override
        public IngestSourceResult ingest(IngestSourceCommand command) {
            attempts++;
            throw new IllegalStateException("boom");
        }
    }

    private static final class FailedAfterFirstAttemptUseCase implements IngestSourceUseCase {
        private final Diagnostic diagnostic;
        private int attempts;

        private FailedAfterFirstAttemptUseCase(Diagnostic diagnostic) {
            this.diagnostic = diagnostic;
        }

        @Override
        public IngestSourceResult ingest(IngestSourceCommand command) {
            attempts++;
            if (attempts == 1) {
                throw new DiagnosticException(diagnostic);
            }
            return new IngestSourceResult(command.key(), IngestionStatus.FAILED, false, null);
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
