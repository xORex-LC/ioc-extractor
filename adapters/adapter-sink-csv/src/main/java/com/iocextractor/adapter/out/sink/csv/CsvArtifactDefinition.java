package com.iocextractor.adapter.out.sink.csv;

import com.iocextractor.domain.model.IndicatorType;

import java.util.Set;

/**
 * Reusable CSV artifact definition. The same row mapping configuration can be
 * rendered either to canonical oneshot artifacts or daemon partition artifacts.
 *
 * @param name artifact name
 * @param accepts accepted indicator types
 * @param filter feature-level routing filter
 * @param mapper row mapper
 * @param idStrategy id generation strategy
 * @param idStart starting id value
 */
public record CsvArtifactDefinition(String name,
                                    Set<IndicatorType> accepts,
                                    ArtifactFilter filter,
                                    RowMapper mapper,
                                    IdGenerator.Strategy idStrategy,
                                    long idStart) {

    public CsvArtifactDefinition {
        filter = filter == null ? ArtifactFilter.none() : filter;
    }

    /**
     * Creates a definition without feature-level filtering.
     */
    public CsvArtifactDefinition(String name,
                                 Set<IndicatorType> accepts,
                                 RowMapper mapper,
                                 IdGenerator.Strategy idStrategy,
                                 long idStart) {
        this(name, accepts, ArtifactFilter.none(), mapper, idStrategy, idStart);
    }
}
