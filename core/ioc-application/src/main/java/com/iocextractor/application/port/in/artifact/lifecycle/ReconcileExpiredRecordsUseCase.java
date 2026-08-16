package com.iocextractor.application.port.in.artifact.lifecycle;

import com.iocextractor.application.artifact.lifecycle.LifecycleReconciliationResult;

/** Driving port for one recoverable bounded expiration reconciliation cycle. */
@FunctionalInterface
public interface ReconcileExpiredRecordsUseCase {

    /** Reconciles all rows due at one safe effective UTC boundary. */
    LifecycleReconciliationResult reconcile();
}
