package com.iocextractor.adapter.out.store.jdbc;

import com.iocextractor.application.aggregation.IngestRun;
import com.iocextractor.application.aggregation.IngestRunStatus;
import com.iocextractor.application.port.out.aggregation.RunLedger;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * JDBC-backed durable run ledger for per-file ingest saga checkpoints.
 */
public final class JdbcRunLedger implements RunLedger {

    private static final String ARTIFACT_SEPARATOR = "\n";

    private final JdbcClient jdbc;
    private final Clock clock;

    public JdbcRunLedger(DataSource dataSource, Clock clock) {
        this(JdbcClient.create(dataSource), clock);
    }

    JdbcRunLedger(JdbcClient jdbc, Clock clock) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public IngestRun startIngest(String sourceKey, List<String> artifacts) {
        String runId = UUID.randomUUID().toString();
        Instant now = clock.instant();
        jdbc.sql("""
                        INSERT INTO ingest_run(run_id, source_key, status, artifacts, started_at, updated_at, reason)
                        VALUES (:run_id, :source_key, :status, :artifacts, :started_at, :updated_at, NULL)
                        """)
                .param("run_id", runId)
                .param("source_key", sourceKey)
                .param("status", IngestRunStatus.STARTED.name())
                .param("artifacts", joinArtifacts(artifacts))
                .param("started_at", now.toString())
                .param("updated_at", now.toString())
                .update();
        return new IngestRun(runId, sourceKey, IngestRunStatus.STARTED, artifacts, now, now, null);
    }

    @Override
    public void markDbCommitted(String runId) {
        update(runId, IngestRunStatus.DB_COMMITTED, null);
    }

    @Override
    public void markProjectionCompleted(String runId) {
        update(runId, IngestRunStatus.PROJECTION_COMPLETED, null);
    }

    @Override
    public void markCompleted(String runId) {
        update(runId, IngestRunStatus.COMPLETED, null);
    }

    @Override
    public void markFailed(String runId, String reason) {
        update(runId, IngestRunStatus.FAILED, reason);
    }

    @Override
    public List<IngestRun> findIncompleteIngestRuns() {
        return jdbc.sql("""
                        SELECT run_id, source_key, status, artifacts, started_at, updated_at, reason
                        FROM ingest_run
                        WHERE status IN ('STARTED', 'DB_COMMITTED', 'PROJECTION_COMPLETED')
                        ORDER BY started_at, run_id
                        """)
                .query((rs, rowNum) -> new IngestRun(
                        rs.getString("run_id"),
                        rs.getString("source_key"),
                        IngestRunStatus.valueOf(rs.getString("status")),
                        splitArtifacts(rs.getString("artifacts")),
                        Instant.parse(rs.getString("started_at")),
                        Instant.parse(rs.getString("updated_at")),
                        rs.getString("reason")))
                .list();
    }

    private void update(String runId, IngestRunStatus status, String reason) {
        jdbc.sql("""
                        UPDATE ingest_run
                        SET status = :status,
                            updated_at = :updated_at,
                            reason = :reason
                        WHERE run_id = :run_id
                        """)
                .param("status", status.name())
                .param("updated_at", clock.instant().toString())
                .param("reason", reason)
                .param("run_id", runId)
                .update();
    }

    private String joinArtifacts(List<String> artifacts) {
        return String.join(ARTIFACT_SEPARATOR, artifacts == null ? List.of() : artifacts);
    }

    private List<String> splitArtifacts(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return Arrays.stream(value.split(ARTIFACT_SEPARATOR))
                .filter(artifact -> !artifact.isBlank())
                .toList();
    }
}
