package com.iocextractor.adapter.in.cli;

import com.iocextractor.application.dataframeimport.model.ImportSnapshotReference;

import java.nio.file.Path;

/** Adapter seam that turns a caller-owned preview file into an ephemeral opaque reference. */
public interface ImportPreviewFileLocator {

    /** Registers one read-only file for the lifetime of the oneshot command context. */
    ImportSnapshotReference reference(Path file);
}
