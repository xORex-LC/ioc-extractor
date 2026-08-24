package com.iocextractor.application.dataframeimport;

import com.iocextractor.platform.concurrent.WorkKey;

/** Stable in-process coordination keys for managed import. */
final class DataframeImportWorkKeys {

    static final WorkKey GLOBAL_LANE = new WorkKey("dataframe-import-global-lane");

    private DataframeImportWorkKeys() {
    }
}
