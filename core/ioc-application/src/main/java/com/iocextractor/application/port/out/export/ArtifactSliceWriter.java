package com.iocextractor.application.port.out.export;

import com.iocextractor.application.export.AvailableSlice;
import com.iocextractor.application.export.ExportRun;
import com.iocextractor.application.export.SliceInspection;
import com.iocextractor.application.export.SnapshotRequest;
import com.iocextractor.application.export.StagedSlice;

/**
 * Driven port for deterministic local slice bytes and their staging/publication protocol.
 *
 * <p>The implementation owns CSV/filesystem details but never changes saga ledger state.
 */
public interface ArtifactSliceWriter {

    StagedSlice stage(ExportRun run, SnapshotRequest request, SnapshotSliceReader reader);

    SliceInspection inspect(ExportRun run);

    StagedSlice recoverStaging(ExportRun run);

    AvailableSlice makeAvailable(ExportRun run);

    void discardStaging(ExportRun run);
}
