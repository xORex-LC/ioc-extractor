package com.iocextractor.application.dataframeimport;

import com.iocextractor.application.dataframeimport.model.ImportDeliveryId;
import com.iocextractor.application.dataframeimport.model.ImportDeliverySequence;
import com.iocextractor.application.dataframeimport.model.ImportSourceId;
import com.iocextractor.platform.events.ControlEvent;
import com.iocextractor.platform.events.ControlEventMetadata;

import java.time.Instant;
import java.util.Objects;

/** Loss-tolerant hint emitted after exact immutable snapshot evidence is durable. */
public record ImportDeliverySnapshotPinned(
        ControlEventMetadata metadata,
        ImportDeliveryId deliveryId,
        ImportDeliverySequence sequence,
        ImportSourceId sourceId) implements ControlEvent {

    public static final String EVENT_TYPE = "dataframe-import.delivery-snapshot.pinned";
    public static final int EVENT_VERSION = 1;

    /** Requires only safe delivery coordination identities. */
    public ImportDeliverySnapshotPinned {
        Objects.requireNonNull(metadata, "metadata");
        Objects.requireNonNull(deliveryId, "deliveryId");
        Objects.requireNonNull(sequence, "sequence");
        Objects.requireNonNull(sourceId, "sourceId");
    }

    /** Creates a snapshot-pinned nudge without locator, filename or digest. */
    public static ImportDeliverySnapshotPinned from(ImportDeliveryId deliveryId,
                                                    ImportDeliverySequence sequence,
                                                    ImportSourceId sourceId,
                                                    Instant occurredAt) {
        Objects.requireNonNull(deliveryId, "deliveryId");
        ControlEventMetadata metadata = ControlEventMetadata.withoutCausation(
                "import-snapshot-pinned:" + deliveryId.value(),
                EVENT_TYPE,
                EVENT_VERSION,
                Objects.requireNonNull(occurredAt, "occurredAt"),
                deliveryId.value());
        return new ImportDeliverySnapshotPinned(metadata, deliveryId, sequence, sourceId);
    }
}
