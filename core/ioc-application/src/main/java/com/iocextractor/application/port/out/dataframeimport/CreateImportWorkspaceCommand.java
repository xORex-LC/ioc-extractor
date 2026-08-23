package com.iocextractor.application.port.out.dataframeimport;

import com.iocextractor.application.dataframeimport.model.ImportContractFingerprint;
import com.iocextractor.application.dataframeimport.model.ImportDeliveryId;
import com.iocextractor.application.dataframeimport.model.ImportSha256;

import java.util.Objects;

/** Request to create private disk-backed scratch state for one pinned delivery. */
public record CreateImportWorkspaceCommand(
        ImportDeliveryId deliveryId,
        ImportSha256 snapshotDigest,
        ImportContractFingerprint contractFingerprint) {

    /** Requires all immutable staging identity inputs. */
    public CreateImportWorkspaceCommand {
        Objects.requireNonNull(deliveryId, "deliveryId");
        Objects.requireNonNull(snapshotDigest, "snapshotDigest");
        Objects.requireNonNull(contractFingerprint, "contractFingerprint");
    }
}
