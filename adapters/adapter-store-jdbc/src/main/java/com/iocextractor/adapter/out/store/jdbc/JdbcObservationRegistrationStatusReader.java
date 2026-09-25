package com.iocextractor.adapter.out.store.jdbc;

import com.iocextractor.application.observation.ObservationRegistrationStatus;
import com.iocextractor.application.port.out.observation.ObservationRegistrationStatusReader;
import com.iocextractor.common.IocExtractorException;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** Indexed aggregate status over the dataframe-owned observation authority. */
public final class JdbcObservationRegistrationStatusReader
        implements ObservationRegistrationStatusReader {

    private final DataSource dataSource;

    public JdbcObservationRegistrationStatusReader(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    @Override
    public ObservationRegistrationStatus status() {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet row = statement.executeQuery("""
                     SELECT COUNT(*) AS pending_total,
                            COALESCE(SUM(CASE WHEN origin_kind = 'ONESHOT' THEN 1 ELSE 0 END), 0)
                                AS pending_oneshot,
                            MIN(CASE WHEN origin_kind = 'ONESHOT' THEN registered_at_ms END)
                                AS oldest_oneshot_ms
                     FROM registered_observation
                     WHERE terminal_at_ms IS NULL
                     """)) {
            if (!row.next()) {
                throw new IllegalStateException("Observation registration status query returned no row");
            }
            long oldest = row.getLong("oldest_oneshot_ms");
            Optional<Instant> oldestAt = row.wasNull()
                    ? Optional.empty() : Optional.of(Instant.ofEpochMilli(oldest));
            return new ObservationRegistrationStatus(
                    row.getLong("pending_total"), row.getLong("pending_oneshot"), oldestAt);
        } catch (SQLException failure) {
            throw new IocExtractorException("Failed to read observation registration status", failure);
        }
    }
}
