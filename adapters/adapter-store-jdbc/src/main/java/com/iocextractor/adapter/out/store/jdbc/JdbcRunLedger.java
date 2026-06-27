package com.iocextractor.adapter.out.store.jdbc;

import com.iocextractor.application.artifact.IngestRun;
import com.iocextractor.application.artifact.IngestRunStatus;
import com.iocextractor.application.port.out.artifact.RunLedger;
import com.iocextractor.common.IocExtractorException;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
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
        Objects.requireNonNull(dataSource, "dataSource");
        this.jdbc = JdbcClient.create(dataSource);
        this.clock = Objects.requireNonNull(clock, "clock");
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
        update(runId, IngestRunStatus.STARTED, IngestRunStatus.DB_COMMITTED, null);
    }

    @Override
    public void markProjectionCompleted(String runId) {
        update(runId, IngestRunStatus.DB_COMMITTED, IngestRunStatus.PROJECTION_COMPLETED, null);
    }

    @Override
    public void markCompleted(String runId) {
        update(runId, IngestRunStatus.PROJECTION_COMPLETED, IngestRunStatus.COMPLETED, null);
    }

    @Override
    public void markFailed(String runId, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Failed ingest run requires a reason");
        }
        update(runId, IngestRunStatus.STARTED, IngestRunStatus.FAILED, reason);
    }

    @Override
    public List<IngestRun> findIncompleteIngestRuns() {
        return jdbc.sql("""
                        SELECT run_id, source_key, status, artifacts, started_at, updated_at, reason
                        FROM ingest_run
                        WHERE status IN ('STARTED', 'DB_COMMITTED', 'PROJECTION_COMPLETED')
                        ORDER BY started_at, run_id
                        """)
                .query(JdbcRunLedger::mapRun)
                .list();
    }

    private void update(String runId,
                        IngestRunStatus expected,
                        IngestRunStatus next,
                        String reason) {
        int affected = jdbc.sql("""
                        UPDATE ingest_run
                        SET status = :next,
                            updated_at = :updated_at,
                            reason = :reason
                        WHERE run_id = :run_id AND status = :expected
                        """)
                .param("next", next.name())
                .param("updated_at", clock.instant().toString())
                .param("reason", reason)
                .param("run_id", runId)
                .param("expected", expected.name())
                .update();
        if (affected == 1) {
            return;
        }
        IngestRun actual = find(runId).orElseThrow(() -> new IocExtractorException(
                "Missing ingest run during transition " + expected + " -> " + next + ": " + runId));
        boolean compatibleReason = next != IngestRunStatus.FAILED || Objects.equals(actual.reason(), reason);
        if (!reachedOrPassed(actual.status(), next) || !compatibleReason) {
            throw new IocExtractorException("Ingest run transition conflict for " + runId
                    + ": expected " + expected + ", actual " + actual.status() + ", next " + next);
        }
    }

    private Optional<IngestRun> find(String runId) {
        return jdbc.sql("""
                        SELECT run_id, source_key, status, artifacts, started_at, updated_at, reason
                        FROM ingest_run
                        WHERE run_id = :run_id
                        """)
                .param("run_id", runId)
                .query(JdbcRunLedger::mapRun)
                .optional();
    }

    private boolean reachedOrPassed(IngestRunStatus actual, IngestRunStatus requested) {
        if (actual == requested) {
            return true;
        }
        return switch (requested) {
            case STARTED -> false;
            case DB_COMMITTED -> actual == IngestRunStatus.PROJECTION_COMPLETED
                    || actual == IngestRunStatus.COMPLETED;
            case PROJECTION_COMPLETED -> actual == IngestRunStatus.COMPLETED;
            case COMPLETED, FAILED -> false;
        };
    }

    private static IngestRun mapRun(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new IngestRun(
                rs.getString("run_id"),
                rs.getString("source_key"),
                IngestRunStatus.valueOf(rs.getString("status")),
                splitArtifacts(rs.getString("artifacts")),
                Instant.parse(rs.getString("started_at")),
                Instant.parse(rs.getString("updated_at")),
                rs.getString("reason"));
    }

    private String joinArtifacts(List<String> artifacts) {
        return String.join(ARTIFACT_SEPARATOR, artifacts == null ? List.of() : artifacts);
    }

    private static List<String> splitArtifacts(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return Arrays.stream(value.split(ARTIFACT_SEPARATOR))
                .filter(artifact -> !artifact.isBlank())
                .toList();
    }
}
