package com.iocextractor.application.dataframeimport.model;

import java.util.Objects;
import java.util.Optional;

/** Immutable snapshot, contract and stage checkpoints accumulated by one delivery. */
public record ImportDeliveryEvidence(
        Optional<ImportSnapshot> snapshot,
        Optional<ImportContractPin> contract,
        Optional<ImportStage> stage) {

    /** Enforces checkpoint dependency order. */
    public ImportDeliveryEvidence {
        snapshot = Objects.requireNonNull(snapshot, "snapshot");
        contract = Objects.requireNonNull(contract, "contract");
        stage = Objects.requireNonNull(stage, "stage");
        if (contract.isPresent() && snapshot.isEmpty()) {
            throw new IllegalArgumentException("Pinned import contract requires a pinned snapshot");
        }
        if (stage.isPresent() && contract.isEmpty()) {
            throw new IllegalArgumentException("Pinned import stage requires a pinned contract");
        }
    }
}
