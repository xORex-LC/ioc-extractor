package com.iocextractor.application.port.out.ingest;

import com.iocextractor.application.ingest.ArchivedSourceUnit;
import com.iocextractor.application.ingest.SourceKey;
import com.iocextractor.application.ingest.SourceUnit;

import java.nio.file.Path;
import java.time.Instant;

/**
 * Filesystem-agnostic source ownership lifecycle.
 */
public interface SourceLifecycle {

    SourceUnit claim(Path source, SourceKey key, Instant detectedAt);

    Path archive(SourceUnit unit);

    Path archive(ArchivedSourceUnit source);

    Path archiveDuplicate(Path source, SourceKey key);

    Path fail(SourceUnit unit, String reason);
}
