package com.iocextractor.application.port.out.sync;

import com.iocextractor.application.sync.PublishRecord;
import com.iocextractor.application.sync.PublishStatus;

import java.util.List;
import java.util.Optional;

/** Durable delivery saga ledger keyed by immutable slice and configured target. */
public interface PublishLedger {

    PublishRecord ensurePending(PublishRecord pending);

    Optional<PublishRecord> find(String sliceId, String targetId);

    List<PublishRecord> findBySlice(String sliceId);

    List<PublishRecord> findRetryable();

    PublishRecord transition(String sliceId,
                             String targetId,
                             PublishStatus expected,
                             PublishStatus next,
                             String lastError,
                             String remoteVerification);
}
