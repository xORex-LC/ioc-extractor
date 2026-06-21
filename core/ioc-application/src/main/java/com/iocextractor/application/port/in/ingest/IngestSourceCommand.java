package com.iocextractor.application.port.in.ingest;

import com.iocextractor.application.ingest.SourceKey;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;

/**
 * Command to process one source file detected by an inbound adapter.
 *
 * @param source original source path
 * @param key source content key
 * @param detectedAt adapter observation timestamp
 */
public record IngestSourceCommand(Path source, SourceKey key, Instant detectedAt) {

    public IngestSourceCommand {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(detectedAt, "detectedAt");
    }
}
