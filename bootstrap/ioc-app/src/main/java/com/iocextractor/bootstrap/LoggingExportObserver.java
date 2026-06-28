package com.iocextractor.bootstrap;

import com.iocextractor.application.export.ExportRun;
import com.iocextractor.application.export.ExportRunStatus;
import com.iocextractor.application.export.StagedSlice;
import com.iocextractor.application.port.out.export.ExportObserver;
import com.iocextractor.observability.EventAction;
import com.iocextractor.observability.EventOutcome;
import com.iocextractor.observability.LogField;
import com.iocextractor.observability.logging.LogEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Bootstrap logging adapter translating export lifecycle callbacks into ECS fields. */
public final class LoggingExportObserver implements ExportObserver {

    private static final Logger log = LoggerFactory.getLogger(LoggingExportObserver.class);

    @Override
    public void started(ExportRun run) {
        event(EventAction.EXPORT_START, run, EventOutcome.UNKNOWN)
                .message("artifact export started")
                .log();
    }

    @Override
    public void sliceWritten(ExportRun run, StagedSlice slice) {
        long revision = slice.manifest().artifacts().stream()
                .mapToLong(artifact -> artifact.coverage().revision()).max().orElse(0);
        event(EventAction.EXPORT_SLICE_WRITE, run, EventOutcome.SUCCESS)
                .field(LogField.IOC_EXPORT_REVISION, revision)
                .message("artifact export slice written")
                .log();
    }

    @Override
    public void completed(ExportRun run) {
        EventOutcome outcome = run.status() == ExportRunStatus.FAILED
                ? EventOutcome.FAILURE : EventOutcome.SUCCESS;
        event(EventAction.EXPORT_COMPLETE, run, outcome)
                .message("artifact export completed")
                .log();
    }

    @Override
    public void recovering(ExportRun run) {
        event(EventAction.EXPORT_RECOVER, run, EventOutcome.UNKNOWN)
                .message("artifact export recovery started")
                .log();
    }

    private com.iocextractor.observability.logging.LogEvent event(EventAction action,
                                                                  ExportRun run,
                                                                  EventOutcome outcome) {
        return LogEvents.info(log)
                .action(action)
                .outcome(outcome)
                .field(LogField.IOC_RUN_ID, run.runId())
                .field(LogField.IOC_EXPORT_PROFILE, run.profile())
                .field(LogField.IOC_EXPORT_SLICE_ID, run.runId());
    }
}
