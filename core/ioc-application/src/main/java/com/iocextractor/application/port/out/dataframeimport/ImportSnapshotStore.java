package com.iocextractor.application.port.out.dataframeimport;

import com.iocextractor.application.dataframeimport.model.ImportDeliveryId;
import com.iocextractor.application.dataframeimport.model.ImportSnapshot;
import com.iocextractor.application.dataframeimport.model.ImportSnapshotReference;

import java.nio.file.Path;

/** Driven port for durable publication and cleanup of immutable local snapshots. */
public interface ImportSnapshotStore {

    /** Publishes or adopts one delivery snapshot using a transport-supplied writer. */
    ImportSnapshot materialize(ImportDeliveryId deliveryId, ImportSnapshotWriter writer);

    /** Resolves only opaque references issued by this store or its legacy predecessors. */
    Path resolve(ImportSnapshotReference reference);

    /** Removes idempotently the final and partial snapshot owned by one delivery. */
    void purge(ImportDeliveryId deliveryId);
}
