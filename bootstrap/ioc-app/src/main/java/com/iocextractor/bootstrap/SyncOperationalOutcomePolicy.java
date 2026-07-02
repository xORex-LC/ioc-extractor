package com.iocextractor.bootstrap;

import com.iocextractor.application.sync.RemoteErrorKind;
import com.iocextractor.application.sync.RemoteTransportException;

/** Maps stable transport failures to bootstrap logging and health semantics. */
public final class SyncOperationalOutcomePolicy {

    /** Classifies retryable connectivity failures as degradation and all other failures as down. */
    public SyncOperationalStatus classify(RuntimeException failure) {
        RemoteTransportException transportFailure = findTransportFailure(failure);
        if (transportFailure == null) {
            return SyncOperationalStatus.DOWN;
        }
        RemoteErrorKind kind = transportFailure.kind();
        return kind == RemoteErrorKind.TRANSIENT || kind == RemoteErrorKind.UNREACHABLE
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
