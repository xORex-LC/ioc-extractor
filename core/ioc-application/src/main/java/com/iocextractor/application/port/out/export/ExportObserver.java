package com.iocextractor.application.port.out.export;

import com.iocextractor.application.export.ExportRun;
import com.iocextractor.application.export.StagedSlice;

/** Operational event boundary for export lifecycle producers. */
public interface ExportObserver {

    /** Called after durable global single-flight has been acquired. */
    void started(ExportRun run);

    /** Called after all staging bytes and their integrity chain are durable. */
    void sliceWritten(ExportRun run, StagedSlice slice);

    /** Called after a run reaches a terminal checkpoint. */
    void completed(ExportRun run);

    /** Called before forward recovery examines one durable incomplete run. */
    void recovering(ExportRun run);
}
