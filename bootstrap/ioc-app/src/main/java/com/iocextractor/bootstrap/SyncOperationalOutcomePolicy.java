package com.iocextractor.bootstrap;

import com.iocextractor.application.sync.RemoteErrorDisposition;
import com.iocextractor.application.sync.RemoteTransportException;

/** Maps stable transport failures to bootstrap logging and health semantics. */
public final class SyncOperationalOutcomePolicy {

    /** Classifies retryable transport failures as degradation and permanent failures as down. */
    public SyncOperationalStatus classify(RuntimeException failure) {
        RemoteTransportException transportFailure = findTransportFailure(failure);
        if (transportFailure == null) {
            return SyncOperationalStatus.DOWN;
        }
        return transportFailure.kind().disposition() != RemoteErrorDisposition.FAIL
                ? SyncOperationalStatus.DEGRADED
                : SyncOperationalStatus.DOWN;
    }

    private RemoteTransportException findTransportFailure(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof RemoteTransportException transportFailure) {
                return transportFailure;
            }
            current = current.getCause();
        }
        return null;
    }
}
