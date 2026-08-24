package com.iocextractor.application.dataframeimport;

/** Safe fail-closed contradiction between ledger, files and dataframe evidence. */
public final class DataframeImportConsistencyException extends IllegalStateException {

    /** Creates a value-free consistency failure. */
    public DataframeImportConsistencyException(String message) {
        super(message);
    }

    /** Creates a value-free consistency failure while retaining its technical cause. */
    public DataframeImportConsistencyException(String message, Throwable cause) {
        super(message, cause);
    }
}
