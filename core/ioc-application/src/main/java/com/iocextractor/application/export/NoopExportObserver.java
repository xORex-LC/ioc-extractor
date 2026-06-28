package com.iocextractor.application.export;

import com.iocextractor.application.port.out.export.ExportObserver;

/** Default export observer used when no operational event adapter is configured. */
public enum NoopExportObserver implements ExportObserver {
    INSTANCE;

    @Override
    public void started(ExportRun run) {
    }

    @Override
    public void sliceWritten(ExportRun run, StagedSlice slice) {
    }

    @Override
    public void completed(ExportRun run) {
    }

    @Override
    public void recovering(ExportRun run) {
    }
}
