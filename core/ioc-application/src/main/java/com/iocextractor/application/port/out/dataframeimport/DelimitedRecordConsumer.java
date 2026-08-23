package com.iocextractor.application.port.out.dataframeimport;

import com.iocextractor.application.dataframeimport.model.ImportDelimitedRecord;

/** Callback receiving one strictly decoded record without materializing the file. */
@FunctionalInterface
public interface DelimitedRecordConsumer {

    /**
     * Accepts one record in monotonically increasing source-row order.
     *
     * @param record decoded record
     */
    void accept(ImportDelimitedRecord record);
}
