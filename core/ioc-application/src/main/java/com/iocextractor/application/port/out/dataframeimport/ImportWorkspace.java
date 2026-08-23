package com.iocextractor.application.port.out.dataframeimport;

/** Driven port for per-delivery disk-backed, rebuildable staging. */
public interface ImportWorkspace {

    /**
     * Opens a new private writer; an existing incompatible workspace must not be overwritten silently.
     *
     * @param command pinned workspace identity
     * @return streaming writer
     */
    ImportWorkspaceWriter create(CreateImportWorkspaceCommand command);
}
