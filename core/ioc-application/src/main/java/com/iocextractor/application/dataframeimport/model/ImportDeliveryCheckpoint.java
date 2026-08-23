package com.iocextractor.application.dataframeimport.model;

import java.util.Objects;
import java.util.Optional;

/** State-specific immutable evidence persisted atomically with a delivery transition. */
public record ImportDeliveryCheckpoint(
        Optional<ImportSnapshot> snapshot,
        Optional<ImportContractPin> contract,
        Optional<ImportStage> stage) {

    private static final ImportDeliveryCheckpoint NONE = new ImportDeliveryCheckpoint(
            Optional.empty(), Optional.empty(), Optional.empty());

    /** Snapshots optional evidence and prevents multiple checkpoint types in one transition. */
    public ImportDeliveryCheckpoint {
        snapshot = Objects.requireNonNull(snapshot, "snapshot");
        contract = Objects.requireNonNull(contract, "contract");
        stage = Objects.requireNonNull(stage, "stage");
        int present = (snapshot.isPresent() ? 1 : 0)
                + (contract.isPresent() ? 1 : 0)
                + (stage.isPresent() ? 1 : 0);
        if (present > 1) {
            throw new IllegalArgumentException("One delivery transition may pin at most one checkpoint");
        }
    }

    /** Returns a transition without new immutable evidence. */
    public static ImportDeliveryCheckpoint none() {
        return NONE;
    }

    /** Returns snapshot evidence for {@code SNAPSHOT_PINNED}. */
    public static ImportDeliveryCheckpoint snapshot(ImportSnapshot snapshot) {
        return new ImportDeliveryCheckpoint(Optional.of(Objects.requireNonNull(snapshot, "snapshot")),
                Optional.empty(), Optional.empty());
    }

    /** Returns contract evidence for {@code CONTRACT_PINNED}. */
    public static ImportDeliveryCheckpoint contract(ImportContractPin contract) {
        return new ImportDeliveryCheckpoint(Optional.empty(),
                Optional.of(Objects.requireNonNull(contract, "contract")), Optional.empty());
    }

    /** Returns sealed-stage evidence for {@code STAGED}. */
    public static ImportDeliveryCheckpoint stage(ImportStage stage) {
        return new ImportDeliveryCheckpoint(Optional.empty(), Optional.empty(),
                Optional.of(Objects.requireNonNull(stage, "stage")));
    }
}
