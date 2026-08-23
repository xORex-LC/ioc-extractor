package com.iocextractor.application.port.in.dataframeimport;

/** Driving port for side-effect-free dataframe import validation and preview. */
public interface ValidateDataframeImportUseCase {

    /**
     * Validates a snapshot without claim, ID/slot reservation, TTL renewal or durable mutation.
     *
     * @param command validation request
     * @return advisory summary
     */
    ValidateDataframeImportResult validate(ValidateDataframeImportCommand command);
}
