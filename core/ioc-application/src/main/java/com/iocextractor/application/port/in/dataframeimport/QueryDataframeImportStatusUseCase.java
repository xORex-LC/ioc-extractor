package com.iocextractor.application.port.in.dataframeimport;

import com.iocextractor.application.dataframeimport.model.ImportDeliveryStatus;

/** Driving port for bounded read-only import status. */
public interface QueryDataframeImportStatusUseCase {

    /**
     * Returns safe aggregate state without paths, digests, filenames or IOC values.
     *
     * @return aggregate status
     */
    ImportDeliveryStatus status();
}
