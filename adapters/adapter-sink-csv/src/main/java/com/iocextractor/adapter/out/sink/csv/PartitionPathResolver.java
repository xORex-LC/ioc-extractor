package com.iocextractor.adapter.out.sink.csv;

import com.iocextractor.application.ingest.SourceUnit;

import java.nio.file.Path;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Resolves deterministic partition artifact paths for daemon-mode source units.
 */
public final class PartitionPathResolver {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ISO_LOCAL_DATE.withZone(ZoneOffset.UTC);

    private final Path partitionsDir;

    public PartitionPathResolver(Path partitionsDir) {
        this.partitionsDir = partitionsDir;
    }

    public Path resolve(SourceUnit source, CsvArtifactDefinition artifact) {
        String day = DAY.format(source.detectedAt());
        return partitionsDir
                .resolve(artifact.name())
                .resolve(day)
                .resolve(source.key().value() + ".csv");
    }
}
