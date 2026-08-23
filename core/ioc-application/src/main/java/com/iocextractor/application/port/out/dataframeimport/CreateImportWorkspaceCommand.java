package com.iocextractor.application.port.out.dataframeimport;

import com.iocextractor.application.dataframeimport.model.ImportContractPin;
import com.iocextractor.application.dataframeimport.model.ImportDeliveryId;
import com.iocextractor.application.dataframeimport.model.ImportDuplicatePolicy;
import com.iocextractor.application.dataframeimport.model.ImportSnapshot;

import java.util.Objects;

/** Request to create private disk-backed scratch state for one pinned delivery. */
public record CreateImportWorkspaceCommand(
        ImportDeliveryId deliveryId,
        ImportSnapshot snapshot,
        ImportContractPin contract,
        ImportDuplicatePolicy duplicatePolicy) {

    /** Requires all immutable staging identity inputs. */
    public CreateImportWorkspaceCommand {
        Objects.requireNonNull(deliveryId, "deliveryId");
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(contract, "contract");
        Objects.requireNonNull(duplicatePolicy, "duplicatePolicy");
    }
}
