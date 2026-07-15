package com.iocextractor.adapter.in.ingest;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.iocextractor.application.ingest.IngestionStatus;
import com.iocextractor.application.ingest.SourceKey;
import com.iocextractor.application.pipeline.CompletionStatus;
import com.iocextractor.application.port.in.ExtractionResult;
import com.iocextractor.application.port.in.ingest.IngestSourceCommand;
import com.iocextractor.application.port.in.ingest.IngestSourceResult;
import com.iocextractor.application.port.in.ingest.IngestSourceUseCase;
import com.iocextractor.application.port.in.ingest.IngestionRejectionResult;
import com.iocextractor.application.port.in.ingest.RejectIngestionUseCase;
import com.iocextractor.common.IocExtractorException;
import com.iocextractor.diagnostics.Diagnostic;
import com.iocextractor.diagnostics.DiagnosticException;
import com.iocextractor.diagnostics.DiagnosticSeverity;
import com.iocextractor.diagnostics.codes.IngestDiagnosticCodes;
import com.iocextractor.diagnostics.result.DiagnosticSummary;
import com.iocextractor.diagnostics.sink.CollectingDiagnosticSink;
import com.iocextractor.observability.EventAction;
import com.iocextractor.observability.EventOutcome;
import com.iocextractor.observability.LogField;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileSourceMessageHandlerTest {

    private final Logger logger = (Logger) LoggerFactory.getLogger(FileSourceMessageHandler.class);

    @TempDir
    Path tempDir;

    @AfterEach
    void resetLogger() {
        logger.detachAndStopAllAppenders();
        logger.setAdditive(true);
        logger.setLevel(null);
    }

    @ParameterizedTest
    @MethodSource("completionOutcomes")
    void logs_structured_terminal_completion_for_extracted_source(
            CompletionStatus status,
            DiagnosticSummary summary,
            Level expectedLevel,
            EventOutcome expectedOutcome,
            String expectedMessage) throws Exception {
        Path source = Files.writeString(tempDir.resolve("source-" + status + ".html"), "ioc");
        var appender = appender();
        var handler = handler(command -> new IngestSourceResult(
                command.key(),
                IngestionStatus.SOURCE_ARCHIVED,
                false,
                new ExtractionResult("run-17", 2, 1, Map.of(), status, List.of(), summary)));

        handler.handle(source.toFile());

        assertThat(appender.list).hasSize(1);
        ILoggingEvent event = appender.list.getFirst();
        assertThat(event.getLevel()).isEqualTo(expectedLevel);
        assertThat(event.getFormattedMessage()).isEqualTo(expectedMessage);
        assertThat(event.getMDCPropertyMap())
                .containsEntry(LogField.EVENT_ACTION.key(), EventAction.SOURCE_INGEST.value())
                .containsEntry(LogField.EVENT_OUTCOME.key(), expectedOutcome.value())
                .containsEntry(LogField.IOC_RUN_ID.key(), "run-17")
                .containsEntry(LogField.IOC_COMPLETION_STATUS.key(), status.toString())
                .containsEntry(LogField.IOC_DIAGNOSTIC_TOTAL.key(), Long.toString(summary.total()))
                .containsEntry(LogField.IOC_DIAGNOSTIC_SUPPRESSED.key(), Long.toString(summary.suppressed()))
                .containsEntry(LogField.IOC_DIAGNOSTIC_FATAL_COUNT.key(), Long.toString(summary.count(DiagnosticSeverity.FATAL)))
                .containsEntry(LogField.IOC_DIAGNOSTIC_ERROR_COUNT.key(), Long.toString(summary.count(DiagnosticSeverity.ERROR)))
                .containsEntry(LogField.IOC_DIAGNOSTIC_WARN_COUNT.key(), Long.toString(summary.count(DiagnosticSeverity.WARN)));
    }

    @Test
    void logs_duplicate_without_fabricating_extraction_completion() throws Exception {
        Path source = Files.writeString(tempDir.resolve("duplicate.html"), "ioc");
        var appender = appender();
        var handler = handler(command -> new IngestSourceResult(
                command.key(), IngestionStatus.SOURCE_ARCHIVED, true, null));

        handler.handle(source.toFile());

        assertThat(appender.list).hasSize(1);
        ILoggingEvent event = appender.list.getFirst();
        assertThat(event.getFormattedMessage()).isEqualTo("source duplicate skipped");
        assertThat(event.getMDCPropertyMap())
                .containsEntry(LogField.EVENT_ACTION.key(), EventAction.SOURCE_INGEST.value())
                .containsEntry(LogField.EVENT_OUTCOME.key(), EventOutcome.SUCCESS.value())
                .doesNotContainKeys(LogField.IOC_RUN_ID.key(), LogField.IOC_COMPLETION_STATUS.key());
    }

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
    void unreadableSourceIsDurablyRejectedAndDiagnosedOnlyOnce() {
        var ingestCalls = new AtomicInteger();
        var reject = new RecordingRejectUseCase();
        var diagnostics = new CollectingDiagnosticSink();
        var handler = new FileSourceMessageHandler(
                new FileSourceHasher(),
                command -> {
                    ingestCalls.incrementAndGet();
                    throw new AssertionError("unreadable source must not reach ingestion");
                },
                reject,
                Clock.fixed(Instant.parse("2026-06-22T00:00:00Z"), ZoneOffset.UTC),
                2,
                Duration.ZERO,
                diagnostics);

        handler.handle(tempDir.toFile());
        SourceKey firstKey = reject.key;
        handler.handle(tempDir.toFile());

        assertThat(ingestCalls).hasValue(0);
        assertThat(reject.attempts).isEqualTo(2);
        assertThat(reject.key).isEqualTo(firstKey);
        assertThat(diagnostics.diagnostics()).singleElement().satisfies(diagnostic -> {
            assertThat(diagnostic.code()).isEqualTo(IngestDiagnosticCodes.SOURCE_UNREADABLE);
            assertThat(diagnostic.context())
                    .containsEntry("source", tempDir)
                    .containsKey("reason");
        });
    }

    @Test
    void interruptedHashRetryIsNotMisclassifiedAsUnreadableSource() {
        var reject = new RecordingRejectUseCase();
        var diagnostics = new CollectingDiagnosticSink();
        var handler = new FileSourceMessageHandler(
                new FileSourceHasher(),
                command -> {
                    throw new AssertionError("unreadable source must not reach ingestion");
                },
                reject,
                Clock.systemUTC(),
                2,
                Duration.ofSeconds(1),
                diagnostics);

        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> handler.handle(tempDir.toFile()))
                    .isInstanceOf(IocExtractorException.class)
                    .hasMessageContaining("Interrupted while waiting for ingest retry");
        } finally {
            Thread.interrupted();
        }

        assertThat(reject.attempts).isZero();
        assertThat(diagnostics.diagnostics()).isEmpty();
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
                (key, reason) -> IngestionRejectionResult.REJECTED,
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

    @Test
    void alreadyRejectedSourceDoesNotCreateAPerPollFailure() throws Exception {
        Path source = Files.writeString(tempDir.resolve("failed.html"), "ioc");
        var reject = new RecordingRejectUseCase();
        var diagnostics = new CollectingDiagnosticSink();
        var handler = new FileSourceMessageHandler(
                new FileSourceHasher(),
                command -> new IngestSourceResult(
                        command.key(), IngestionStatus.FAILED, false, null),
                reject,
                Clock.systemUTC(),
                2,
                Duration.ZERO,
                diagnostics);

        handler.handle(source.toFile());

        assertThat(reject.attempts).isZero();
        assertThat(diagnostics.diagnostics()).isEmpty();
    }

    private FileSourceMessageHandler handler(IngestSourceUseCase useCase) {
        return new FileSourceMessageHandler(
                new FileSourceHasher(),
                useCase,
                (key, reason) -> IngestionRejectionResult.REJECTED,
                Clock.systemUTC(),
                1,
                Duration.ZERO,
                new CollectingDiagnosticSink());
    }

    private ListAppender<ILoggingEvent> appender() {
        logger.detachAndStopAllAppenders();
        logger.setAdditive(false);
        logger.setLevel(Level.TRACE);
        var appender = new PreparingListAppender();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    private static Stream<Arguments> completionOutcomes() {
        return Stream.of(
                Arguments.of(CompletionStatus.COMPLETED, DiagnosticSummary.empty(),
                        Level.INFO, EventOutcome.SUCCESS, "source ingested"),
                Arguments.of(CompletionStatus.COMPLETED_WITH_WARNINGS,
                        new DiagnosticSummary(1, 0, Map.of(DiagnosticSeverity.WARN, 1L)),
                        Level.WARN, EventOutcome.SUCCESS, "source ingested with warnings"),
                Arguments.of(CompletionStatus.COMPLETED_WITH_ERRORS,
                        new DiagnosticSummary(2, 1, Map.of(DiagnosticSeverity.ERROR, 2L)),
                        Level.WARN, EventOutcome.FAILURE, "source ingested with errors"));
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
        private int attempts;

        @Override
        public IngestionRejectionResult reject(SourceKey key, String reason) {
            attempts++;
            this.key = key;
            this.reason = reason;
            return attempts == 1
                    ? IngestionRejectionResult.REJECTED
                    : IngestionRejectionResult.ALREADY_REJECTED;
        }
    }

    private static final class PreparingListAppender extends ListAppender<ILoggingEvent> {

        @Override
        protected void append(ILoggingEvent eventObject) {
            eventObject.prepareForDeferredProcessing();
            super.append(eventObject);
        }
    }
}
