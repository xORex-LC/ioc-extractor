package com.iocextractor.bootstrap;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.iocextractor.application.export.ArtifactCoverage;
import com.iocextractor.application.export.ExportFormat;
import com.iocextractor.application.export.ExportMode;
import com.iocextractor.application.export.ExportRun;
import com.iocextractor.application.export.ExportRunStatus;
import com.iocextractor.application.export.SliceArtifactManifest;
import com.iocextractor.application.export.SliceManifest;
import com.iocextractor.application.export.StagedSlice;
import com.iocextractor.observability.EventAction;
import com.iocextractor.observability.EventOutcome;
import com.iocextractor.observability.LogField;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LoggingExportObserverTest {

    private static final Instant NOW = Instant.parse("2026-06-28T00:00:00Z");
    private static final String HASH = "a".repeat(64);

    private final Logger logger = (Logger) LoggerFactory.getLogger(LoggingExportObserver.class);

    @AfterEach
    void detachAppenders() {
        logger.detachAndStopAllAppenders();
        logger.setAdditive(true);
    }

    @Test
    void emitsWhitelistedEcsActionsAndExportCorrelationFields() {
        var appender = appender();
        var observer = new LoggingExportObserver();
        ExportRun started = ExportRun.started(
                "run-1", "reputation", "slice-1", HASH, NOW);

        observer.started(started);
        observer.sliceWritten(started, staged());
        observer.recovering(started);
        observer.completed(new ExportRun(
                "run-1", "reputation", ExportRunStatus.FAILED, "slice-1", HASH,
                null, NOW, NOW.plusSeconds(1), "failure"));

        assertThat(appender.list).extracting(event -> event.getMDCPropertyMap()
                        .get(LogField.EVENT_ACTION.key()))
                .containsExactly(
                        EventAction.EXPORT_START.value(),
                        EventAction.EXPORT_SLICE_WRITE.value(),
                        EventAction.EXPORT_RECOVER.value(),
                        EventAction.EXPORT_COMPLETE.value());
        assertThat(appender.list).allSatisfy(event -> assertThat(event.getMDCPropertyMap())
                .containsEntry(LogField.IOC_RUN_ID.key(), "run-1")
                .containsEntry(LogField.IOC_EXPORT_PROFILE.key(), "reputation")
                .containsEntry(LogField.IOC_EXPORT_SLICE_ID.key(), "run-1"));
        assertThat(appender.list.get(1).getMDCPropertyMap())
                .containsEntry(LogField.IOC_EXPORT_REVISION.key(), "7");
        assertThat(appender.list.getLast().getMDCPropertyMap())
                .containsEntry(LogField.EVENT_OUTCOME.key(), EventOutcome.FAILURE.value());
    }

    private StagedSlice staged() {
        SliceManifest manifest = new SliceManifest(
                1, "run-1", "run-1", "reputation", NOW, ExportMode.COMPLETE, HASH,
                new ExportFormat("csv", "UTF-8", ";", "\"", "NULL"),
                List.of(new SliceArtifactManifest(
                        "masks", "masks.csv", 1,
                        new ArtifactCoverage(7, NOW, 1), 1, HASH, HASH, HASH)));
        return new StagedSlice("run-1", "run-1", HASH, manifest);
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

    private static final class PreparingListAppender extends ListAppender<ILoggingEvent> {
        @Override
        protected void append(ILoggingEvent eventObject) {
            eventObject.prepareForDeferredProcessing();
            super.append(eventObject);
        }
    }
}
