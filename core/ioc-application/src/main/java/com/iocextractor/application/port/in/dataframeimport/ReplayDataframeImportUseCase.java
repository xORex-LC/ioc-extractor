package com.iocextractor.application.port.in.dataframeimport;

/** Driving port for full replay as a new delivery; terminal state is never reopened. */
public interface ReplayDataframeImportUseCase {

    /**
     * Reserves a new occurrence using a protected terminal source/report unit.
     *
     * @param command replay request
     * @return new durable delivery
     */
    ReplayDataframeImportResult replay(ReplayDataframeImportCommand command);
}
