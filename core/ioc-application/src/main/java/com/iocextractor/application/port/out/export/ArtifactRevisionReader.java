package com.iocextractor.application.port.out.export;

import com.iocextractor.application.export.ArtifactRevision;

import java.util.List;

/** Cheap read port for current canonical revisions outside full materialization. */
public interface ArtifactRevisionReader {

    /** Returns revisions in the requested artifact order. */
    List<ArtifactRevision> read(List<String> artifacts);
}
