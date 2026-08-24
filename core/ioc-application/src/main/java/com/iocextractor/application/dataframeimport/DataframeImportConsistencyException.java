package com.iocextractor.application.dataframeimport;

/** Safe fail-closed contradiction between ledger, files and dataframe evidence. */
public final class DataframeImportConsistencyException extends IllegalStateException {

    /** Creates a value-free consistency failure. */
    public DataframeImportConsistencyException(String message) {
        super(message);
    }
}
