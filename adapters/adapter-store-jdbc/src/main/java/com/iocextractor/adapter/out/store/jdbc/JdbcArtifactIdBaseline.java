package com.iocextractor.adapter.out.store.jdbc;

import com.iocextractor.application.port.out.artifact.ArtifactIdBaseline;
import com.iocextractor.common.IocExtractorException;
import org.springframework.jdbc.core.simple.JdbcClient;

import javax.sql.DataSource;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Reads public artifact id baselines from configured JDBC dataframe schemas.
 */
public final class JdbcArtifactIdBaseline implements ArtifactIdBaseline {

    private static final String ID_COLUMN = "id";

    private final JdbcClient jdbc;
    private final Set<String> artifactsWithPublicId;

    public JdbcArtifactIdBaseline(DataSource dataSource, List<DataframeArtifactSchema> schemas) {
        Objects.requireNonNull(dataSource, "dataSource");
        Objects.requireNonNull(schemas, "schemas");
        this.jdbc = JdbcClient.create(dataSource);
        this.artifactsWithPublicId = schemas.stream()
                .filter(JdbcArtifactIdBaseline::hasPublicIdColumn)
                .map(DataframeArtifactSchema::artifactName)
                .collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public long maxId(String artifactName) {
        String validatedName = DataframeColumn.requireSqlIdentifier(artifactName, "artifact name");
        if (!artifactsWithPublicId.contains(validatedName)) {
            return 0L;
        }
        try {
            return jdbc.sql("SELECT MAX(" + quote(ID_COLUMN) + ") FROM " + quote(validatedName))
                    .query(Long.class)
                    .optional()
                    .orElse(0L);
        } catch (RuntimeException e) {
            throw new IocExtractorException("Failed to read max id for JDBC artifact: " + validatedName, e);
        }
    }

    private static boolean hasPublicIdColumn(DataframeArtifactSchema schema) {
        return schema.columns().stream()
                .anyMatch(column -> ID_COLUMN.equals(column.name()));
    }

    private static String quote(String identifier) {
        return "\"" + DataframeColumn.requireSqlIdentifier(identifier, "identifier") + "\"";
    }
}
