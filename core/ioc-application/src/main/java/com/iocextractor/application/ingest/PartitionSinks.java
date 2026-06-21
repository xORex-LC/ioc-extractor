package com.iocextractor.application.ingest;

import com.iocextractor.application.port.out.IocSink;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Sink set and resolved output paths for one source partition.
 *
 * @param sinks sinks to pass into the existing extraction pipeline
 * @param paths concrete partition artifact paths created by those sinks
 */
public record PartitionSinks(List<IocSink> sinks, List<Path> paths) {

    public PartitionSinks {
        Objects.requireNonNull(sinks, "sinks");
        Objects.requireNonNull(paths, "paths");
        sinks = List.copyOf(sinks);
        paths = List.copyOf(paths);
    }
}
