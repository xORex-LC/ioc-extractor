package com.iocextractor.application.port.out.export;

import com.iocextractor.application.artifact.ArtifactRow;
import com.iocextractor.application.export.SnapshotArtifactMetadata;
import com.iocextractor.application.export.SnapshotMetadata;

/**
 * Synchronous callback boundary from a canonical snapshot reader to a streaming formatter.
 *
 * <p>Consumers must not retain rows or invoke callbacks asynchronously. The reader owns the
 * transaction and resources for the full callback sequence.
 */
public interface SnapshotRowConsumer {

    void begin(SnapshotMetadata metadata);

    void beginArtifact(SnapshotArtifactMetadata artifact);

    void row(ArtifactRow row);

    void endArtifact();

    void end();
}
