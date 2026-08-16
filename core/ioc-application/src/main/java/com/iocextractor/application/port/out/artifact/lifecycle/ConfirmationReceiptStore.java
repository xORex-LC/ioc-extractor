package com.iocextractor.application.port.out.artifact.lifecycle;

import com.iocextractor.application.artifact.lifecycle.ConfirmationReceiptSnapshot;
import com.iocextractor.application.artifact.lifecycle.EffectiveTime;

import java.util.Optional;

/** Durable lookup and bounded cleanup of prepared-row confirmation receipts. */
public interface ConfirmationReceiptStore {

    Optional<ConfirmationReceiptSnapshot> findComplete(
            String sourceKey, String processingPolicyFingerprint, EffectiveTime asOf);

    PurgeResult purgeExpired(EffectiveTime asOf, int batchSize);

    record PurgeResult(int purged, boolean moreEligible) {
        public PurgeResult {
            if (purged < 0) {
                throw new IllegalArgumentException("Purged receipt count must not be negative");
            }
        }
    }
}
