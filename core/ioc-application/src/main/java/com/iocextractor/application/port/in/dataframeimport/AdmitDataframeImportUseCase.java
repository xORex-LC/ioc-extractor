package com.iocextractor.application.port.in.dataframeimport;

/** Driving port for source candidate admission and claim-order reservation. */
public interface AdmitDataframeImportUseCase {

    /**
     * Reserves one occurrence before transport workers may finish out of order.
     *
     * @param command candidate reservation
     * @return durable admission result
     */
    AdmitDataframeImportResult admit(AdmitDataframeImportCommand command);
}
