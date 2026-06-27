package com.iocextractor.adapter.out.store.jdbc;

import com.iocextractor.application.export.ExportProgress;
import com.iocextractor.application.port.out.export.ExportProgressStore;
import org.springframework.jdbc.core.simple.JdbcClient;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** JDBC read side for the last terminal export progress of a profile. */
public final class JdbcExportProgressStore implements ExportProgressStore {

    private final JdbcClient jdbc;

    public JdbcExportProgressStore(DataSource dataSource) {
        Objects.requireNonNull(dataSource, "dataSource");
        this.jdbc = JdbcClient.create(dataSource);
    }

    JdbcExportProgressStore(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public List<ExportProgress> findByProfile(String profile) {
        if (profile == null || profile.isBlank()) {
            throw new IllegalArgumentException("Export profile must not be blank");
        }
        return jdbc.sql("""
                        SELECT profile, artifact, last_revision, last_sha256,
                               last_slice_id, plan_hash, updated_at
                        FROM export_progress
                        WHERE profile = :profile
                        ORDER BY artifact
                        """)
                .param("profile", profile)
                .query((rs, rowNum) -> new ExportProgress(
                        rs.getString("profile"),
                        rs.getString("artifact"),
                        rs.getLong("last_revision"),
                        rs.getString("last_sha256"),
                        rs.getString("last_slice_id"),
                        rs.getString("plan_hash"),
                        Instant.parse(rs.getString("updated_at"))))
                .list();
    }
}
