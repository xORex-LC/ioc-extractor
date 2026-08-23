package com.iocextractor.application.port.out.artifact;

import com.iocextractor.application.artifact.ArtifactRow;

/** Synchronous callback for one row from a canonical artifact cursor. */
@FunctionalInterface
public interface CanonicalArtifactRowConsumer {

    /** Accepts one row before the storage cursor advances. */
    void accept(ArtifactRow row);
}
