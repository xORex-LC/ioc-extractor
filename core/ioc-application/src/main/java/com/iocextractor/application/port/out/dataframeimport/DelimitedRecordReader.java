package com.iocextractor.application.port.out.dataframeimport;

/** Driven port hiding the concrete CSV parser and decoder implementation. */
public interface DelimitedRecordReader {

    /**
     * Streams strict decoded records to the supplied callback and never silently replaces invalid bytes.
     *
     * @param command parse request
     * @param consumer record callback
     */
    void read(DelimitedReadCommand command, DelimitedRecordConsumer consumer);
}
