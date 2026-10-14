package com.iocextractor.application.port.out.dataframeimport;

import com.iocextractor.application.dataframeimport.model.ImportContractPin;
import com.iocextractor.application.dataframeimport.model.ImportDeliveryId;
import com.iocextractor.application.dataframeimport.model.ImportDeliverySequence;
import com.iocextractor.application.dataframeimport.model.ImportSnapshot;
import com.iocextractor.application.dataframeimport.model.ImportSourceId;
import com.iocextractor.application.dataframeimport.model.ImportStage;
import com.iocextractor.application.observation.RegisteredObservation;

import java.util.Objects;

/** Complete integrity-pinned write set submitted to one dataframe transaction. */
public record CanonicalImportCommand(
        ImportDeliveryId deliveryId,
        ImportDeliverySequence sequence,
        ImportSourceId sourceId,
        ImportSnapshot snapshot,
        ImportContractPin contract,
        ImportStage stage,
        RegisteredObservation registration) {

    /** Requires complete promotion evidence. */
    public CanonicalImportCommand {
        Objects.requireNonNull(deliveryId, "deliveryId");
        Objects.requireNonNull(sequence, "sequence");
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(contract, "contract");
        Objects.requireNonNull(stage, "stage");
    }

    /** Compatibility constructor for import writers without ordered-field policies. */
    public CanonicalImportCommand(ImportDeliveryId deliveryId,
                                  ImportDeliverySequence sequence,
                                  ImportSourceId sourceId,
                                  ImportSnapshot snapshot,
                                  ImportContractPin contract,
                                  ImportStage stage) {
        this(deliveryId, sequence, sourceId, snapshot, contract, stage, null);
    }
}
