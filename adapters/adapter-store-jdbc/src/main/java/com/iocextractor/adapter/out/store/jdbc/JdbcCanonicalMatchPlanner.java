package com.iocextractor.adapter.out.store.jdbc;

import com.iocextractor.application.artifact.CanonicalMatchPlan;
import com.iocextractor.application.artifact.CanonicalMatchRequest;
import com.iocextractor.application.artifact.lifecycle.EffectiveTime;
import com.iocextractor.application.port.out.artifact.CanonicalMatchPlanner;
import com.iocextractor.common.IocExtractorException;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** SQLite set-based active alias matcher. */
public final class JdbcCanonicalMatchPlanner implements CanonicalMatchPlanner {

    private final DataSource dataSource;
    private final Map<String, DataframeArtifactSchema> schemas;

    /** Creates a planner for the configured dataframe artifacts. */
    public JdbcCanonicalMatchPlanner(DataSource dataSource, List<DataframeArtifactSchema> schemas) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        var indexed = new LinkedHashMap<String, DataframeArtifactSchema>();
        for (DataframeArtifactSchema schema : List.copyOf(Objects.requireNonNull(schemas, "schemas"))) {
            if (indexed.putIfAbsent(schema.artifactName(), schema) != null) {
                throw new IllegalArgumentException("Duplicate dataframe schema: " + schema.artifactName());
            }
        }
        this.schemas = Map.copyOf(indexed);
    }

    @Override
    public List<CanonicalMatchPlan> plan(String artifactName,
                                         EffectiveTime asOf,
                                         List<CanonicalMatchRequest> requests) {
        DataframeArtifactSchema schema = schemas.get(artifactName);
        if (schema == null) {
            throw new IocExtractorException("Unknown dataframe artifact: " + artifactName);
        }
        try (Connection connection = dataSource.getConnection()) {
            return plan(connection, schema, asOf, requests);
        } catch (SQLException e) {
            throw new IocExtractorException("Failed to plan canonical matches for " + artifactName, e);
        }
    }

    List<CanonicalMatchPlan> plan(Connection connection,
                                  DataframeArtifactSchema schema,
                                  EffectiveTime asOf,
                                  List<CanonicalMatchRequest> requests) throws SQLException {
        try (var session = openSession(connection, schema, asOf)) {
            return session.plan(requests);
        }
    }

    JdbcCanonicalMatchSession openSession(Connection connection,
                                         DataframeArtifactSchema schema,
                                         EffectiveTime asOf) {
        return new JdbcCanonicalMatchSession(connection, schema, asOf);
    }
}
