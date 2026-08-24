package com.iocextractor.application.port.out.dataframeimport;

import com.iocextractor.application.dataframeimport.model.ImportDeliveryId;

import java.nio.file.Path;

/** Retention boundary for one protected source/report terminal unit. */
public interface ImportTerminalRetentionStore {

    /** Idempotently removes one expired terminal unit. */
    void delete(ImportDeliveryId deliveryId);

    /** Idempotently moves one expired terminal unit into protected archive storage. */
    void archive(ImportDeliveryId deliveryId, Path archiveDirectory);
}
