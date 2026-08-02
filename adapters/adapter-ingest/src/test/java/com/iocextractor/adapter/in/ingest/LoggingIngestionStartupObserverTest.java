package com.iocextractor.adapter.in.ingest;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.iocextractor.diagnostics.Diagnostic;
import com.iocextractor.diagnostics.DiagnosticException;
import com.iocextractor.diagnostics.codes.IngestDiagnosticCodes;
import com.iocextractor.diagnostics.sink.CollectingDiagnosticSink;
import com.iocextractor.observability.EventAction;
import com.iocextractor.observability.EventOutcome;
import com.iocextractor.observability.LogField;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LoggingIngestionStartupObserverTest {

    private static final Instant STARTED = Instant.parse("2026-08-02T00:00:00Z");
    private static final Instant COMPLETED = STARTED.plusSeconds(2);
    private static final Clock CLOCK = Clock.fixed(COMPLETED, ZoneOffset.UTC);

    private final Logger logger = (Logger) LoggerFactory.getLogger(LoggingIngestionStartupObserver.class);

    @AfterEach
    void resetLogger() {
        logger.detachAndStopAllAppenders();
        logger.setAdditive(true);
        logger.setLevel(null);
    }

    @Test
    void emitsTypedStartAndCompletionEventsWithRecoveryCounts() {
        var appender = appender();
        var observer = new LoggingIngestionStartupObserver(new CollectingDiagnosticSink(), CLOCK);

        observer.recoveryStarted(STARTED);
        observer.recoveryCompleted(STARTED, COMPLETED, 2, 3);

        assertThat(appender.list).hasSize(2);
        assertThat(eventFields(appender.list.getFirst()))
                .containsEntry(LogField.EVENT_ACTION.key(), EventAction.INGEST_RECOVER.value())
                .containsEntry(LogField.EVENT_OUTCOME.key(), EventOutcome.UNKNOWN.value())
                .doesNotContainKeys(
                        LogField.EVENT_DURATION.key(),
                        LogField.IOC_INGEST_RECOVERED_RUNS.key(),
                        LogField.IOC_INGEST_RECOVERED_SOURCES.key());
        assertThat(eventFields(appender.list.getLast()))
                .containsEntry(LogField.EVENT_ACTION.key(), EventAction.INGEST_RECOVER.value())
                .containsEntry(LogField.EVENT_OUTCOME.key(), EventOutcome.SUCCESS.value())
                .containsEntry(LogField.EVENT_DURATION.key(), 2_000_000_000L)
                .containsEntry(LogField.IOC_INGEST_RECOVERED_RUNS.key(), 2L)
                .containsEntry(LogField.IOC_INGEST_RECOVERED_SOURCES.key(), 3L);
    }

    @Test
    void reportsUntypedStartupFailureOnceWithoutPuttingItsMessageInTheLifecycleEvent() {
        var appender = appender();
        var diagnostics = new CollectingDiagnosticSink();
        var observer = new LoggingIngestionStartupObserver(diagnostics, CLOCK);
        var failure = new IllegalStateException("sensitive/source/path");

        observer.recoveryFailed(STARTED, COMPLETED, failure);

        assertThat(appender.list).singleElement().satisfies(event -> {
            assertThat(event.getFormattedMessage())
                    .isEqualTo("ingestion startup recovery failed")
                    .doesNotContain("sensitive/source/path");
            assertThat(eventFields(event))
                    .containsEntry(LogField.EVENT_ACTION.key(), EventAction.INGEST_RECOVER.value())
                    .containsEntry(LogField.EVENT_OUTCOME.key(), EventOutcome.FAILURE.value())
                    .containsEntry(LogField.EVENT_DURATION.key(), 2_000_000_000L)
                    .containsEntry(LogField.ERROR_TYPE.key(), IllegalStateException.class.getName());
        });
        assertThat(diagnostics.diagnostics()).singleElement().satisfies(diagnostic -> {
            assertThat(diagnostic.code()).isEqualTo(IngestDiagnosticCodes.RECOVERY_FAILED);
            assertThat(diagnostic.context())
                    .containsEntry("source", "startup")
                    .containsEntry("reason", "IllegalStateException");
            assertThat(diagnostic.cause()).contains(failure);
        });
    }

    @Test
    void doesNotEmitAnAlreadyDeliveredRecoveryDiagnosticAgain() {
        var diagnostics = new CollectingDiagnosticSink();
        Diagnostic diagnostic = Diagnostic.builder(IngestDiagnosticCodes.RECOVERY_FAILED, CLOCK)
                .with("source", "recovery-scan")
                .with("reason", "ledger unavailable")
                .build();
        diagnostics.emit(diagnostic);
        var observer = new LoggingIngestionStartupObserver(diagnostics, CLOCK);

        observer.recoveryFailed(STARTED, COMPLETED, new DiagnosticException(diagnostic));

        assertThat(diagnostics.diagnostics()).containsExactly(diagnostic);
    }

    @Test
    void emitsTypedTransitionConflictAsTheRecoveryRootCause() {
        var diagnostics = new CollectingDiagnosticSink();
        Diagnostic conflict = Diagnostic.builder(IngestDiagnosticCodes.STATE_TRANSITION_CONFLICT, CLOCK)
                .with("source", "ABC123")
                .with("operation", "mark-source-archived")
                .with("transition", "CONFLICT")
                .with("expected", "APPLIED or ALREADY_APPLIED")
                .build();
        var observer = new LoggingIngestionStartupObserver(diagnostics, CLOCK);

        observer.recoveryFailed(STARTED, COMPLETED, new DiagnosticException(conflict));

        assertThat(diagnostics.diagnostics()).containsExactly(conflict);
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

    private static Map<String, Object> eventFields(ILoggingEvent event) {
        var fields = new LinkedHashMap<String, Object>();
        event.getKeyValuePairs().forEach(pair -> fields.put(pair.key, pair.value));
        return fields;
    }

    private static final class PreparingListAppender extends ListAppender<ILoggingEvent> {
        @Override
        protected void append(ILoggingEvent eventObject) {
            eventObject.prepareForDeferredProcessing();
            super.append(eventObject);
        }
    }
}
