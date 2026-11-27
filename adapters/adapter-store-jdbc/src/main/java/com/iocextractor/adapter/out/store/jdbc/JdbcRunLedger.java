package com.iocextractor.adapter.out.store.jdbc;

import com.iocextractor.application.artifact.DocumentRun;
import com.iocextractor.application.artifact.IngestRun;
import com.iocextractor.application.artifact.lifecycle.ObservationId;
import com.iocextractor.application.ingest.SourceKey;
import com.iocextractor.application.observation.ObservationOrder;
import com.iocextractor.application.observation.ObservationOrigin;
import com.iocextractor.application.observation.RegisteredObservation;
import com.iocextractor.application.artifact.IngestRunStatus;
import com.iocextractor.application.port.out.artifact.DocumentRunLedger;
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
public final class JdbcRunLedger implements DocumentRunLedger {

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
                        WHERE observation_id IS NULL
                          AND status IN ('STARTED', 'DB_COMMITTED', 'PROJECTION_COMPLETED')
                        ORDER BY started_at, run_id
                        """)
                .query(JdbcRunLedger::mapRun)
                .list();
    }

    @Override
    public Optional<DocumentRun> findDocument(ObservationId id) {
        return jdbc.sql("""
                        SELECT run_id, source_key, status, artifacts, started_at, updated_at, reason,
                               observation_id, dataframe_namespace, admission_order, policy_fingerprint,
                               lifecycle_active, plan_kind, plan_token
                        FROM ingest_run WHERE observation_id = :id
                        """)
                .param("id", id.value())
                .query(JdbcRunLedger::mapDocument)
                .optional();
    }

    @Override
    public DocumentRun beginDocument(DocumentRun.Identity identity) {
        var registration = identity.registration();
        var durable = findDocument(registration.observationId()).orElseGet(() -> insertDocument(identity));
        if (!durable.identity().equals(identity)) {
            throw new IocExtractorException("Conflicting document run identity");
        }
        return durable;
    }

    private DocumentRun insertDocument(DocumentRun.Identity identity) {
        var registration = identity.registration();
        String now = clock.instant().toString();
        jdbc.sql("""
                        INSERT INTO ingest_run (
                            run_id, source_key, status, artifacts, started_at, updated_at,
                            observation_id, dataframe_namespace, admission_order, policy_fingerprint, lifecycle_active
                        ) VALUES (:run, :source, 'STARTED', '', :now, :now, :id, :namespace, :rank, :policy, :fixed)
                        ON CONFLICT(observation_id) WHERE observation_id IS NOT NULL DO NOTHING
                        """)
                .param("run", UUID.randomUUID().toString())
                .param("source", identity.sourceKey().value())
                .param("now", now)
                .param("id", registration.observationId().value())
                .param("namespace", registration.namespaceId())
                .param("rank", registration.admissionOrder().value())
                .param("policy", identity.policy())
                .param("fixed", identity.lifecycleActive() ? 1 : 0)
                .update();
        return findDocument(registration.observationId()).orElseThrow();
    }

    @Override
    public DocumentRun pinPlan(DocumentRun run, DocumentRun.Plan plan) {
        var registration = run.identity().registration();
        jdbc.sql("""
                        UPDATE ingest_run SET plan_kind = :kind, plan_token = :token,
                            artifacts = :artifacts, updated_at = :now
                        WHERE run_id = :run AND status = 'STARTED' AND plan_kind IS NULL
                          AND observation_id = :id AND dataframe_namespace = :namespace AND admission_order = :rank
                          AND source_key = :source AND policy_fingerprint = :policy AND lifecycle_active = :fixed
                        """)
                .param("kind", plan.kind().name())
                .param("token", plan.token())
                .param("artifacts", joinArtifacts(plan.artifacts()))
                .param("now", clock.instant().toString())
                .param("run", run.run().runId())
                .param("id", registration.observationId().value())
                .param("namespace", registration.namespaceId())
                .param("rank", registration.admissionOrder().value())
                .param("source", run.identity().sourceKey().value())
                .param("policy", run.identity().policy())
                .param("fixed", run.identity().lifecycleActive() ? 1 : 0)
                .update();
        var durable = findDocument(registration.observationId()).orElseThrow();
        if (!durable.run().runId().equals(run.run().runId()) || !durable.identity().equals(run.identity())
                || !durable.plan().equals(Optional.of(plan))) {
            throw new IocExtractorException("Conflicting document run plan");
        }
        return durable;
    }

    private static DocumentRun mapDocument(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        IngestRun run = mapRun(rs, row);
        var registration = new RegisteredObservation(new ObservationId(rs.getString("observation_id")),
                rs.getString("dataframe_namespace"), new ObservationOrder(rs.getLong("admission_order")),
                ObservationOrigin.DOCUMENT);
        var identity = new DocumentRun.Identity(registration, new SourceKey(run.sourceKey()),
                rs.getString("policy_fingerprint"), rs.getInt("lifecycle_active") == 1);
        String kind = rs.getString("plan_kind");
        return new DocumentRun(run, identity, kind == null ? Optional.empty()
                : Optional.of(new DocumentRun.Plan(DocumentRun.Plan.Kind.valueOf(kind),
                        rs.getString("plan_token"), run.artifacts())));
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
                          AND (:next <> 'FAILED' OR plan_kind IS NULL)
                          AND (observation_id IS NULL OR :next = 'FAILED' OR plan_kind IS NOT NULL)
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

    private static IngestRun mapRun(java.sql.ResultSet rs, int ignoredRowNum) throws java.sql.SQLException {
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
