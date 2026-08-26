package com.iocextractor.adapter.in.csv;

import com.iocextractor.application.dataframeimport.contract.DelimitedInputReadException;

/** Safe adapter failure for malformed encoding, delimiter grammar or header structure. */
public final class DelimitedRecordReadException extends DelimitedInputReadException {

    /** Creates a structural failure without echoing input values. */
    public DelimitedRecordReadException(Reason reason, String message) {
        super(reason, message);
    }

    /** Creates an I/O or decoder failure without echoing input values. */
    public DelimitedRecordReadException(Reason reason, String message, Throwable cause) {
        super(reason, message, cause);
    }
}
