package com.iocextractor.bootstrap;

import com.iocextractor.observability.EventAction;
import com.iocextractor.observability.EventOutcome;
import com.iocextractor.observability.LogField;
import com.iocextractor.observability.logging.LogEvents;
import com.iocextractor.platform.concurrent.KeyedSerialExecutorObserver;
import com.iocextractor.platform.concurrent.WorkAdmission;
import com.iocextractor.platform.concurrent.WorkKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.RejectedExecutionException;

/** Emits operational signals for the in-memory sync keyed executor. */
public final class SyncKeyedExecutorObserver implements KeyedSerialExecutorObserver {

    private static final Logger log = LoggerFactory.getLogger(SyncKeyedExecutorObserver.class);

    private final SyncHealthState healthState;

    public SyncKeyedExecutorObserver(SyncHealthState healthState) {
        this.healthState = Objects.requireNonNull(healthState, "healthState");
    }

    @Override
    public void rejected(WorkAdmission admission) {
        Objects.requireNonNull(admission, "admission");
        healthState.recordKeyedRejection(admission);
        LogEvents.warn(log)
                .action(EventAction.SYNC_WORK_ADMISSION)
                .outcome(EventOutcome.FAILURE)
                .field(LogField.IOC_SYNC_KEY, admission.key().value())
                .field(LogField.IOC_SYNC_QUEUE_DEPTH, admission.queuedDepth())
                .field(LogField.IOC_SYNC_SHED_TO_RECONCILE, true)
                .message("sync keyed work rejected; reconcile/backstop must recover")
                .log();
    }

    @Override
    public void failed(WorkKey key, RuntimeException failure) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(failure, "failure");
        healthState.recordKeyedFailure(key, failure);
        LogEvents.error(log)
                .action(EventAction.SYNC_WORK_DISPATCH)
                .outcome(EventOutcome.FAILURE)
                .field(LogField.IOC_SYNC_KEY, key.value())
                .message("sync keyed work failed")
                .log(failure);
    }

    @Override
    public void dispatchRejected(WorkKey key, int abandonedWork, RejectedExecutionException failure) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(failure, "failure");
        healthState.recordKeyedDispatchRejected(key, abandonedWork, failure);
        LogEvents.error(log)
                .action(EventAction.SYNC_WORK_DISPATCH)
                .outcome(EventOutcome.FAILURE)
                .field(LogField.IOC_SYNC_KEY, key.value())
                .field(LogField.IOC_SYNC_ABANDONED_WORK, abandonedWork)
                .field(LogField.IOC_SYNC_SHED_TO_RECONCILE, true)
                .message("sync keyed work dispatch rejected; reconcile/backstop must recover")
                .log(failure);
    }
}
