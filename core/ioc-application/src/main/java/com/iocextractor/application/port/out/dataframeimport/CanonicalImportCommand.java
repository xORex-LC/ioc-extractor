package com.iocextractor.application.port.out.dataframeimport;

import com.iocextractor.application.dataframeimport.model.ImportContractFingerprint;
import com.iocextractor.application.dataframeimport.model.ImportDeliveryId;
import com.iocextractor.application.dataframeimport.model.ImportDeliverySequence;
import com.iocextractor.application.dataframeimport.model.ImportSha256;
import com.iocextractor.application.dataframeimport.model.ImportStage;

import java.util.Objects;

/** Complete integrity-pinned write set submitted to one dataframe transaction. */
public record CanonicalImportCommand(
        ImportDeliveryId deliveryId,
        ImportDeliverySequence sequence,
        ImportSha256 snapshotDigest,
        ImportContractFingerprint contractFingerprint,
        ImportStage stage) {

    /** Requires complete promotion evidence. */
    public CanonicalImportCommand {
        Objects.requireNonNull(deliveryId, "deliveryId");
        Objects.requireNonNull(sequence, "sequence");
        Objects.requireNonNull(snapshotDigest, "snapshotDigest");
        Objects.requireNonNull(contractFingerprint, "contractFingerprint");
        Objects.requireNonNull(stage, "stage");
    }
}
