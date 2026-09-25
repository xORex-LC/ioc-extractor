package com.iocextractor.application.port.out.dataframeimport;

import com.iocextractor.application.dataframeimport.model.ImportContractPin;
import com.iocextractor.application.dataframeimport.model.ImportDeliveryId;
import com.iocextractor.application.dataframeimport.model.ImportDuplicatePolicy;
import com.iocextractor.application.dataframeimport.model.ImportPromotionPolicy;
import com.iocextractor.application.dataframeimport.model.ImportSnapshot;

import java.util.Objects;

/** Request to create private disk-backed scratch state for one pinned delivery. */
public record CreateImportWorkspaceCommand(
        ImportDeliveryId deliveryId,
        ImportSnapshot snapshot,
        ImportContractPin contract,
        ImportDuplicatePolicy duplicatePolicy,
        String duplicateSelectionColumn,
        ImportPromotionPolicy promotionPolicy) {

    /** Requires all immutable staging identity inputs. */
    public CreateImportWorkspaceCommand {
        Objects.requireNonNull(deliveryId, "deliveryId");
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(contract, "contract");
        Objects.requireNonNull(duplicatePolicy, "duplicatePolicy");
        Objects.requireNonNull(promotionPolicy, "promotionPolicy");
        if (duplicatePolicy == ImportDuplicatePolicy.LAST_NONEMPTY
                && (duplicateSelectionColumn == null || duplicateSelectionColumn.isBlank())) {
            throw new IllegalArgumentException("Last-nonempty duplicate policy requires a selection column");
        }
        if (duplicatePolicy != ImportDuplicatePolicy.LAST_NONEMPTY
                && duplicateSelectionColumn != null && !duplicateSelectionColumn.isBlank()) {
            throw new IllegalArgumentException("Duplicate selection column requires last-nonempty policy");
        }
    }

    /** Creates a command for duplicate policies that need no selection column. */
    public CreateImportWorkspaceCommand(ImportDeliveryId deliveryId,
                                        ImportSnapshot snapshot,
                                        ImportContractPin contract,
                                        ImportDuplicatePolicy duplicatePolicy,
                                        ImportPromotionPolicy promotionPolicy) {
        this(deliveryId, snapshot, contract, duplicatePolicy, null, promotionPolicy);
    }

    /** Compatibility constructor for adapter fixtures that do not exercise promotion. */
    public CreateImportWorkspaceCommand(ImportDeliveryId deliveryId,
                                        ImportSnapshot snapshot,
                                        ImportContractPin contract,
                                        ImportDuplicatePolicy duplicatePolicy) {
        this(deliveryId, snapshot, contract, duplicatePolicy, null, ImportPromotionPolicy.defaults());
    }

    /** Compatibility constructor for adapter fixtures exercising selection reduction. */
    public CreateImportWorkspaceCommand(ImportDeliveryId deliveryId,
                                        ImportSnapshot snapshot,
                                        ImportContractPin contract,
                                        ImportDuplicatePolicy duplicatePolicy,
                                        String duplicateSelectionColumn) {
        this(deliveryId, snapshot, contract, duplicatePolicy,
                duplicateSelectionColumn, ImportPromotionPolicy.defaults());
    }
}
