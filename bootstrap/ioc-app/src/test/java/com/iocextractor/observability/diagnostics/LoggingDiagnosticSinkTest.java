package com.iocextractor.observability.diagnostics;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.iocextractor.diagnostics.Diagnostic;
import com.iocextractor.diagnostics.DiagnosticSeverity;
import com.iocextractor.diagnostics.codes.PipelineDiagnosticCodes;
import com.iocextractor.observability.EventAction;
import com.iocextractor.observability.EventOutcome;
import com.iocextractor.observability.LogField;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class LoggingDiagnosticSinkTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-06-21T00:00:00Z"), ZoneOffset.UTC);

    private final Logger logger = (Logger) LoggerFactory.getLogger("test.diagnostic-sink");

    @AfterEach
    void detachAppenders() {
        logger.detachAndStopAllAppenders();
    }

    @Test
    void maps_diagnostic_to_log_level_message_and_mdc_fields() {
        var appender = appender();
        var diagnostic = diagnostic(DiagnosticSeverity.WARN, null);
        var sink = new LoggingDiagnosticSink(logger, ignored -> "rendered diagnostic");

        sink.emit(diagnostic);

        assertThat(appender.list).hasSize(1);
        ILoggingEvent event = appender.list.getFirst();
        assertThat(event.getLevel()).isEqualTo(Level.WARN);
        assertThat(event.getFormattedMessage()).isEqualTo("rendered diagnostic");
        assertThat(event.getMDCPropertyMap())
                .containsEntry(LogField.EVENT_ACTION.key(), EventAction.DIAGNOSTIC_EMIT.value())
                .containsEntry(LogField.EVENT_OUTCOME.key(), EventOutcome.UNKNOWN.value())
                .containsEntry(LogField.IOC_DIAGNOSTIC_CODE.key(), PipelineDiagnosticCodes.STAGE_FAILED.id())
                .containsEntry(LogField.IOC_DIAGNOSTIC_CATEGORY.key(), "PIPELINE")
                .containsEntry(LogField.IOC_DIAGNOSTIC_SEVERITY.key(), "WARN");
    }

    @Test
    void maps_error_diagnostic_cause_to_throwable_proxy() {
        var appender = appender();
        var cause = new IllegalStateException("boom");
        var sink = new LoggingDiagnosticSink(logger, ignored -> "rendered diagnostic");

        sink.emit(diagnostic(DiagnosticSeverity.ERROR, cause));

        ILoggingEvent event = appender.list.getFirst();
        assertThat(event.getLevel()).isEqualTo(Level.ERROR);
        assertThat(event.getThrowableProxy()).isNotNull();
        assertThat(event.getThrowableProxy().getMessage()).isEqualTo("boom");
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

    private Diagnostic diagnostic(DiagnosticSeverity severity, Throwable cause) {
        var builder = Diagnostic.builder(PipelineDiagnosticCodes.STAGE_FAILED, CLOCK)
                .severity(severity)
                .with("stage", "extract")
                .with("reason", "failed");
        if (cause != null) {
            builder.cause(cause);
        }
        return builder.build();
    }

    private static final class PreparingListAppender extends ListAppender<ILoggingEvent> {

        @Override
        protected void append(ILoggingEvent eventObject) {
            eventObject.prepareForDeferredProcessing();
            super.append(eventObject);
        }
    }
}
