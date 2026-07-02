package com.iocextractor.bootstrap;

import com.iocextractor.application.sync.RemoteErrorKind;
import com.iocextractor.application.sync.RemoteTransportException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SyncOperationalOutcomePolicyTest {

    private final SyncOperationalOutcomePolicy policy = new SyncOperationalOutcomePolicy();

    @Test
    void classifiesRetryableConnectivityFailuresAsDegraded() {
        assertThat(policy.classify(failure(RemoteErrorKind.TRANSIENT)))
                .isEqualTo(SyncOperationalStatus.DEGRADED);
        assertThat(policy.classify(failure(RemoteErrorKind.UNREACHABLE)))
                .isEqualTo(SyncOperationalStatus.DEGRADED);
    }

    @Test
    void classifiesPermanentAndUnexpectedFailuresAsDown() {
        assertThat(policy.classify(failure(RemoteErrorKind.AUTH_FAILED)))
                .isEqualTo(SyncOperationalStatus.DOWN);
        assertThat(policy.classify(new IllegalStateException("broken contract")))
                .isEqualTo(SyncOperationalStatus.DOWN);
    }

    @Test
    void findsTransportTaxonomyThroughWrapperExceptions() {
        RuntimeException wrapped = new IllegalStateException("operation failed",
                failure(RemoteErrorKind.TRANSIENT));

        assertThat(policy.classify(wrapped)).isEqualTo(SyncOperationalStatus.DEGRADED);
    }

    private RemoteTransportException failure(RemoteErrorKind kind) {
        return new RemoteTransportException(kind, "remote failure");
    }
}
