package com.iocextractor.application.port.out.dataframeimport;

import java.util.List;

/** Driven port hiding the concrete CSV parser and decoder implementation. */
public interface DelimitedRecordReader {

    /**
     * Strictly decodes only the header using one candidate charset/dialect.
     *
     * @param command header request
     * @return external headers in physical order
     */
    List<String> readHeader(DelimitedHeaderReadCommand command);

    /**
     * Streams strict decoded records to the supplied callback and never silently replaces invalid bytes.
     *
     * @param command parse request
     * @param consumer record callback
     */
    void read(DelimitedReadCommand command, DelimitedRecordConsumer consumer);
}
