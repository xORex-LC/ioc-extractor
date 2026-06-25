package com.iocextractor.application.ingest;

import com.iocextractor.application.port.out.IocSink;

import java.util.List;
import java.util.Objects;

/**
 * Sink set scoped to one claimed source.
 *
 * @param sinks output sinks for the extraction pipeline
 */
public record SourceSinks(List<IocSink> sinks) {

    public SourceSinks {
        Objects.requireNonNull(sinks, "sinks");
        sinks = List.copyOf(sinks);
    }

    public List<String> artifactNames() {
        return sinks.stream()
                .map(IocSink::name)
                .toList();
    }
}
