package com.iocextractor.adapter.out.sink.csv;

import com.iocextractor.application.ingest.PartitionSinks;
import com.iocextractor.application.ingest.SourceUnit;
import com.iocextractor.application.port.out.IocSink;
import com.iocextractor.application.port.out.ingest.PartitionSinkFactory;
import org.apache.commons.csv.CSVFormat;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Adapter-level partition wrapper around existing CSV sink pieces.
 */
public final class PartitionedCsvSinkFactory implements PartitionSinkFactory {

    private final List<CsvArtifactDefinition> artifacts;
    private final CSVFormat format;
    private final Charset charset;
    private final PartitionPathResolver pathResolver;

    public PartitionedCsvSinkFactory(Path partitionsDir,
                                     List<CsvArtifactDefinition> artifacts,
                                     CSVFormat format) {
        this(partitionsDir, artifacts, format, StandardCharsets.UTF_8);
    }

    public PartitionedCsvSinkFactory(Path partitionsDir,
                                     List<CsvArtifactDefinition> artifacts,
                                     CSVFormat format,
                                     Charset charset) {
        this.artifacts = List.copyOf(artifacts);
        this.format = format;
        this.charset = charset == null ? StandardCharsets.UTF_8 : charset;
        this.pathResolver = new PartitionPathResolver(partitionsDir);
    }

    @Override
    public PartitionSinks createFor(SourceUnit source) {
        List<IocSink> sinks = new ArrayList<>();
        List<Path> paths = new ArrayList<>();
        for (CsvArtifactDefinition artifact : artifacts) {
            Path path = pathResolver.resolve(source, artifact);
            paths.add(path);
            sinks.add(new CsvIocSink(
                    artifact.name(),
                    path,
                    artifact.accepts(),
                    artifact.filter(),
                    artifact.mapper(),
                    new IdGenerator(artifact.idStrategy(), artifact.idStart()),
                    format,
                    charset));
        }
        return new PartitionSinks(sinks, paths);
    }
}
