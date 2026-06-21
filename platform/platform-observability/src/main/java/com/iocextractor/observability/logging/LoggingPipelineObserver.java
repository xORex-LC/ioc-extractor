package com.iocextractor.observability.logging;

import com.iocextractor.application.pipeline.EnvelopeMeta;
import com.iocextractor.application.pipeline.PipelineObserver;
import com.iocextractor.observability.EventAction;
import com.iocextractor.observability.EventOutcome;
import com.iocextractor.observability.LogField;
import com.iocextractor.observability.MdcScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Pipeline observer that emits operational stage log events.
 */
public final class LoggingPipelineObserver implements PipelineObserver {

    private static final Logger log = LoggerFactory.getLogger(LoggingPipelineObserver.class);

    @Override
    public AutoCloseable openStage(EnvelopeMeta meta) {
        return MdcScope.open()
                .put(LogField.IOC_RUN_ID, meta.runId())
                .put(LogField.IOC_SOURCE_ID, meta.sourceId())
                .put(LogField.IOC_SOURCE_PATH, meta.sourcePath())
                .put(LogField.IOC_STAGE, meta.stage().value())
                .put(LogField.IOC_MODE, meta.attributes().get(EnvelopeMeta.MODE));
    }

    @Override
    public void stageStarted(EnvelopeMeta meta) {
        LogEvents.debug(log)
                .action(EventAction.STAGE_START)
                .outcome(EventOutcome.UNKNOWN)
                .message("stage started")
                .log();
    }

    @Override
    public void stageCompleted(EnvelopeMeta meta, long durationNanos) {
        LogEvents.debug(log)
                .action(EventAction.STAGE_COMPLETE)
                .outcome(EventOutcome.SUCCESS)
                .durationNanos(durationNanos)
                .message("stage completed")
                .log();
    }

    @Override
    public void stageFailed(EnvelopeMeta meta, long durationNanos, RuntimeException failure) {
        LogEvents.error(log)
                .action(EventAction.STAGE_COMPLETE)
                .outcome(EventOutcome.FAILURE)
                .durationNanos(durationNanos)
                .message("stage failed")
                .log(failure);
    }
}
