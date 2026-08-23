package com.iocextractor.adapter.in.csv;

import com.iocextractor.application.dataframeimport.model.ImportSnapshotReference;

import java.nio.file.Path;

/** Resolves an adapter-owned immutable snapshot reference to a readable local path. */
@FunctionalInterface
public interface ImportSnapshotPathResolver {

    /** Resolves one pinned reference without changing snapshot ownership. */
    Path resolve(ImportSnapshotReference reference);
}
