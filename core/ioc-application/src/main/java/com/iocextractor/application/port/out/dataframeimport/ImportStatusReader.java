package com.iocextractor.application.port.out.dataframeimport;

import com.iocextractor.application.dataframeimport.model.ImportDeliveryStatus;

/** Driven port for indexed, bounded and value-free import status aggregates. */
public interface ImportStatusReader {

    /**
     * Reads aggregate state without exposing locators, digests, filenames or IOC values.
     *
     * @return safe status snapshot
     */
    ImportDeliveryStatus readStatus();
}
