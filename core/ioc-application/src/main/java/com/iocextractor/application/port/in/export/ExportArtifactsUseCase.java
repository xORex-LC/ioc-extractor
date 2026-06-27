package com.iocextractor.application.port.in.export;

/** Primary port for materializing one configured immutable export slice. */
public interface ExportArtifactsUseCase {

    /**
     * Executes one profile run or reports a deterministic skip when bytes did not change.
     *
     * @param command selected export profile
     * @return terminal run result
     */
    ExportArtifactsResult export(ExportArtifactsCommand command);
}
