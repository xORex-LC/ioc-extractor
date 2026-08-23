package com.iocextractor.application.port.out.dataframeimport;

/** Driven port for atomic protected report and terminal delivery-unit publication. */
public interface ImportReportStore {

    /**
     * Idempotently publishes a safe report and the source/report terminal unit.
     *
     * @param command bounded value-free report request
     */
    void publish(PublishImportReportCommand command);
}
