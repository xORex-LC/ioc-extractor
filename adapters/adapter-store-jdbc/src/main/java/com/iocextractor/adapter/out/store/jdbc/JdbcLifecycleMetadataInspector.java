package com.iocextractor.adapter.out.store.jdbc;

import com.iocextractor.common.IocExtractorException;
import org.springframework.jdbc.core.simple.JdbcClient;

import javax.sql.DataSource;
import java.util.Objects;

/** Performs set-based activation invariant checks without materializing canonical rows. */
final class JdbcLifecycleMetadataInspector {

    private final JdbcClient jdbc;

    JdbcLifecycleMetadataInspector(DataSource dataSource) {
        this.jdbc = JdbcClient.create(Objects.requireNonNull(dataSource, "dataSource"));
    }

    LifecycleMetadataSummary inspect(String artifactName) {
        String artifact = DataframeColumn.requireSqlIdentifier(artifactName, "artifact name");
        try {
            return jdbc.sql("""
                            SELECT COUNT(*) AS total_rows,
                                   COALESCE(SUM(CASE WHEN
                                       _lifecycle_id IS NULL
                                       AND _first_confirmed_at_epoch_ms IS NULL
                                       AND _last_confirmed_at_epoch_ms IS NULL
                                       AND _valid_until_epoch_ms IS NULL
                                       THEN 1 ELSE 0 END), 0) AS legacy_rows,
                                   COALESCE(SUM(CASE WHEN
                                       _lifecycle_id > 0
                                       AND _first_confirmed_at_epoch_ms IS NOT NULL
                                       AND _last_confirmed_at_epoch_ms IS NOT NULL
                                       AND _valid_until_epoch_ms IS NOT NULL
                                       AND _first_confirmed_at_epoch_ms <= _last_confirmed_at_epoch_ms
                                       AND _last_confirmed_at_epoch_ms < _valid_until_epoch_ms
                                       THEN 1 ELSE 0 END), 0) AS complete_rows
                            FROM """ + quote(artifact))
                    .query((resultSet, rowNumber) -> {
                        long total = resultSet.getLong("total_rows");
                        long legacy = resultSet.getLong("legacy_rows");
                        long complete = resultSet.getLong("complete_rows");
                        return new LifecycleMetadataSummary(
                                artifact, total, legacy, complete, total - legacy - complete);
                    })
                    .single();
        } catch (RuntimeException e) {
            throw new IocExtractorException(
                    "Failed to inspect lifecycle metadata for artifact: " + artifact, e);
        }
    }

    void requireActivationReady(String artifactName) {
        LifecycleMetadataSummary summary = inspect(artifactName);
        if (!summary.activationReady()) {
            throw new IocExtractorException(
                    "Lifecycle metadata is not activation-ready for artifact: " + summary.artifactName()
                            + " (legacy=" + summary.legacyRows()
                            + ", invalid=" + summary.invalidRows() + ")");
        }
    }

    private String quote(String identifier) {
        return "\"" + DataframeColumn.requireSqlIdentifier(identifier, "identifier") + "\"";
    }

    record LifecycleMetadataSummary(String artifactName,
                                    long totalRows,
                                    long legacyRows,
                                    long completeRows,
                                    long invalidRows) {

        LifecycleMetadataSummary {
            Objects.requireNonNull(artifactName, "artifactName");
            if (totalRows < 0 || legacyRows < 0 || completeRows < 0 || invalidRows < 0
                    || totalRows != legacyRows + completeRows + invalidRows) {
                throw new IllegalArgumentException("Invalid lifecycle metadata counts");
            }
        }

        boolean activationReady() {
            return legacyRows == 0 && invalidRows == 0;
        }
    }
}
