package com.iocextractor.application.port.in;

/**
 * Primary (driving) port: the single entry point of the application core.
 * Inbound adapters (CLI today, REST later) depend on this, not on the service.
 */
public interface ExtractIocsUseCase {

    ExtractionResult extract(ExtractionCommand command);
}
