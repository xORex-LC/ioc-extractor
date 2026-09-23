package com.iocextractor.adapter.out.sink.csv;

import com.iocextractor.domain.model.IndicatorType;
import com.iocextractor.application.artifact.ArtifactIdStrategy;
import com.iocextractor.application.artifact.policy.ArtifactWritePolicy;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Reusable CSV artifact definition. The same row mapping configuration can be
 * rendered either to direct CSV artifacts or dataframe CSV projections.
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
                                    ArtifactIdStrategy idStrategy,
                                    long idStart,
                                    ArtifactWritePolicy writePolicy) {

    public CsvArtifactDefinition {
        accepts = accepts == null ? null : Collections.unmodifiableSet(new LinkedHashSet<>(accepts));
        filter = filter == null ? ArtifactFilter.none() : filter;
        writePolicy = writePolicy == null ? ArtifactWritePolicy.legacy() : writePolicy;
    }

    /**
     * Creates a definition without feature-level filtering.
     */
    public CsvArtifactDefinition(String name,
                                 Set<IndicatorType> accepts,
                                 RowMapper mapper,
                                 ArtifactIdStrategy idStrategy,
                                 long idStart) {
        this(name, accepts, ArtifactFilter.none(), mapper, idStrategy, idStart, ArtifactWritePolicy.legacy());
    }

    /** Creates a legacy definition with feature filtering. */
    public CsvArtifactDefinition(String name,
                                 Set<IndicatorType> accepts,
                                 ArtifactFilter filter,
                                 RowMapper mapper,
                                 ArtifactIdStrategy idStrategy,
                                 long idStart) {
        this(name, accepts, filter, mapper, idStrategy, idStart, ArtifactWritePolicy.legacy());
    }
}
