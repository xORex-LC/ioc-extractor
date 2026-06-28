package com.iocextractor.adapter.out.store.jdbc;

import com.iocextractor.application.export.ExportProgress;
import com.iocextractor.application.export.ExportRun;
import com.iocextractor.application.export.ExportRunStatus;
import com.iocextractor.application.port.out.export.ExportRunLedger;
import com.iocextractor.application.port.out.export.ExportRunReader;
import com.iocextractor.diagnostics.Diagnostic;
import com.iocextractor.diagnostics.DiagnosticException;
import com.iocextractor.diagnostics.DiagnosticFactory;
import com.iocextractor.diagnostics.codes.ExportDiagnosticCodes;
import com.iocextractor.diagnostics.sink.DiagnosticSink;
import com.iocextractor.diagnostics.sink.NoopDiagnosticSink;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * SQLite-backed export formation ledger with database-enforced global
 * single-flight and compare-and-set state transitions.
 *
 * <p>Terminal progress and the corresponding {@code COMPLETED}/{@code SKIPPED}
 * checkpoint are committed in one service-database transaction.
 */
public final class JdbcExportRunLedger implements ExportRunLedger, ExportRunReader {

    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final DiagnosticSink diagnosticSink;
    private final DiagnosticFactory diagnosticFactory;

    public JdbcExportRunLedger(DataSource dataSource, Clock clock) {
        this(dataSource, clock, NoopDiagnosticSink.INSTANCE, new DiagnosticFactory(clock));
    }

    public JdbcExportRunLedger(DataSource dataSource,
                               Clock clock,
                               DiagnosticSink diagnosticSink,
                               DiagnosticFactory diagnosticFactory) {
        Objects.requireNonNull(dataSource, "dataSource");
        this.jdbc = JdbcClient.create(dataSource);
        this.transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        this.clock = Objects.requireNonNull(clock, "clock");
        this.diagnosticSink = Objects.requireNonNull(diagnosticSink, "diagnosticSink");
        this.diagnosticFactory = Objects.requireNonNull(diagnosticFactory, "diagnosticFactory");
    }

    @Override
    public Optional<ExportRun> tryStart(ExportRun startedRun) {
        Objects.requireNonNull(startedRun, "startedRun");
        if (startedRun.status() != ExportRunStatus.STARTED) {
            throw new IllegalArgumentException("Export ledger accepts only STARTED runs");
        }
        try {
            jdbc.sql("""
                            INSERT INTO export_run(
                                run_id, profile, status, slice_name, plan_hash,
                                manifest_sha256, started_at, updated_at, reason)
                            VALUES (
                                :run_id, :profile, :status, :slice_name, :plan_hash,
                                NULL, :started_at, :updated_at, NULL)
                            """)
                    .param("run_id", startedRun.runId())
                    .param("profile", startedRun.profile())
                    .param("status", startedRun.status().name())
                    .param("slice_name", startedRun.sliceName())
                    .param("plan_hash", startedRun.planHash())
                    .param("started_at", startedRun.startedAt().toString())
                    .param("updated_at", startedRun.updatedAt().toString())
                    .update();
            return Optional.of(startedRun);
        } catch (DataAccessException conflict) {
            Optional<ExportRun> existing = find(startedRun.runId());
            if (existing.isPresent()) {
                if (sameStart(existing.get(), startedRun)) {
                    return existing;
                }
                throw conflict(startedRun.runId(), ExportRunStatus.STARTED,
                        ExportRunStatus.STARTED, existing.get().status().name());
            }
            if (findActive().isPresent()) {
                return Optional.empty();
            }
            throw conflict;
        }
    }

    @Override
    public ExportRun transition(String runId,
                                ExportRunStatus expected,
                                ExportRunStatus next,
                                String manifestSha256,
                                String reason) {
        requireTransition(expected, next);
        if (next == ExportRunStatus.COMPLETED || next == ExportRunStatus.SKIPPED) {
            throw new IllegalArgumentException("Terminal progress transitions must use finish");
        }
        validateTransitionData(next, manifestSha256, reason);
        int affected = jdbc.sql("""
                        UPDATE export_run
                        SET status = :next,
                            manifest_sha256 = COALESCE(:manifest_sha256, manifest_sha256),
                            updated_at = :updated_at,
                            reason = :reason
                        WHERE run_id = :run_id AND status = :expected
                        """)
                .param("next", next.name())
                .param("manifest_sha256", manifestSha256)
                .param("updated_at", clock.instant().toString())
                .param("reason", reason)
                .param("run_id", requireText(runId, "runId"))
                .param("expected", expected.name())
                .update();
        if (affected == 1) {
            return required(runId, expected, next);
        }
        return resolveReplay(runId, expected, next, manifestSha256, reason);
    }

    @Override
    public ExportRun finish(String runId,
                            ExportRunStatus expected,
                            ExportRunStatus terminal,
                            List<ExportProgress> progress) {
        requireFinish(expected, terminal);
        List<ExportProgress> terminalProgress = validateProgress(progress);
        ExportRun result = transactions.execute(status -> {
            int affected = jdbc.sql("""
                            UPDATE export_run
                            SET status = :terminal,
                                updated_at = :updated_at,
                                reason = NULL
                            WHERE run_id = :run_id AND status = :expected
                            """)
                    .param("terminal", terminal.name())
                    .param("updated_at", clock.instant().toString())
                    .param("run_id", requireText(runId, "runId"))
                    .param("expected", expected.name())
                    .update();
            if (affected == 0) {
                ExportRun replay = resolveReplay(runId, expected, terminal, null, null);
                verifyProgress(replay.profile(), terminalProgress, expected, terminal, runId);
                return replay;
            }

            ExportRun run = required(runId, expected, terminal);
            writeProgress(run.profile(), terminalProgress);
            return run;
        });
        return Objects.requireNonNull(result, "transaction result");
    }

    @Override
    public Optional<ExportRun> find(String runId) {
        return jdbc.sql("""
                        SELECT run_id, profile, status, slice_name, plan_hash,
                               manifest_sha256, started_at, updated_at, reason
                        FROM export_run
                        WHERE run_id = :run_id
                        """)
                .param("run_id", requireText(runId, "runId"))
                .query(JdbcExportRunLedger::mapRun)
                .optional();
    }

    @Override
    public List<ExportRun> findIncomplete() {
        return jdbc.sql("""
                        SELECT run_id, profile, status, slice_name, plan_hash,
                               manifest_sha256, started_at, updated_at, reason
                        FROM export_run
                        WHERE status IN ('STARTED', 'STAGED', 'AVAILABLE')
                        ORDER BY started_at, run_id
                        """)
                .query(JdbcExportRunLedger::mapRun)
                .list();
    }

    @Override
    public Optional<ExportRun> findLatest(String profile, ExportRunStatus status) {
        return jdbc.sql("""
                        SELECT run_id, profile, status, slice_name, plan_hash,
                               manifest_sha256, started_at, updated_at, reason
                        FROM export_run
                        WHERE profile = :profile AND status = :status
                        ORDER BY julianday(updated_at) DESC, run_id DESC
                        LIMIT 1
                        """)
                .param("profile", requireText(profile, "profile"))
                .param("status", Objects.requireNonNull(status, "status").name())
                .query(JdbcExportRunLedger::mapRun)
                .optional();
    }

    private Optional<ExportRun> findActive() {
        return jdbc.sql("""
                        SELECT run_id, profile, status, slice_name, plan_hash,
                               manifest_sha256, started_at, updated_at, reason
                        FROM export_run
                        WHERE status IN ('STARTED', 'STAGED', 'AVAILABLE')
                        LIMIT 1
                        """)
                .query(JdbcExportRunLedger::mapRun)
                .optional();
    }

    private ExportRun resolveReplay(String runId,
                                    ExportRunStatus expected,
                                    ExportRunStatus next,
                                    String manifestSha256,
                                    String reason) {
        ExportRun actual = find(runId).orElseThrow(() -> conflict(runId, expected, next, "MISSING"));
        if (reachedOrPassed(actual.status(), next)
                && compatibleManifest(actual, manifestSha256)
                && compatibleReason(actual, next, reason)) {
            return actual;
        }
        throw conflict(runId, expected, next, actual.status().name());
    }

    private ExportRun required(String runId, ExportRunStatus expected, ExportRunStatus next) {
        return find(runId).orElseThrow(() -> conflict(runId, expected, next, "MISSING"));
    }

    private void writeProgress(String profile, List<ExportProgress> progress) {
        for (ExportProgress item : progress) {
            if (!profile.equals(item.profile())) {
                throw new IllegalArgumentException("Export progress profile must match run profile");
            }
            int affected = jdbc.sql("""
                            INSERT INTO export_progress(
                                profile, artifact, last_revision, last_sha256,
                                last_slice_id, plan_hash, updated_at)
                            VALUES (
                                :profile, :artifact, :last_revision, :last_sha256,
                                :last_slice_id, :plan_hash, :updated_at)
                            ON CONFLICT(profile, artifact) DO UPDATE SET
                                last_revision = excluded.last_revision,
                                last_sha256 = excluded.last_sha256,
                                last_slice_id = excluded.last_slice_id,
                                plan_hash = excluded.plan_hash,
                                updated_at = excluded.updated_at
                            WHERE excluded.last_revision >= export_progress.last_revision
                            """)
                    .param("profile", item.profile())
                    .param("artifact", item.artifactName())
                    .param("last_revision", item.lastRevision())
                    .param("last_sha256", item.lastSha256())
                    .param("last_slice_id", item.lastSliceId())
                    .param("plan_hash", item.planHash())
                    .param("updated_at", item.updatedAt().toString())
                    .update();
            if (affected != 1) {
                throw new IllegalArgumentException("Export progress revision must not move backwards: "
                        + item.profile() + "/" + item.artifactName());
            }
        }
    }

    private void verifyProgress(String profile,
                                List<ExportProgress> expectedProgress,
                                ExportRunStatus expectedStatus,
                                ExportRunStatus terminal,
                                String runId) {
        List<ExportProgress> persisted = new JdbcExportProgressStore(jdbc).findByProfile(profile);
        boolean allMatch = expectedProgress.stream().allMatch(expectedItem -> persisted.stream()
                .anyMatch(actualItem -> sameProgress(actualItem, expectedItem)));
        if (!allMatch) {
            throw conflict(runId, expectedStatus, terminal, terminal.name());
        }
    }

    private boolean sameProgress(ExportProgress actual, ExportProgress expected) {
        return actual.profile().equals(expected.profile())
                && actual.artifactName().equals(expected.artifactName())
                && actual.lastRevision() == expected.lastRevision()
                && actual.lastSha256().equals(expected.lastSha256())
                && actual.lastSliceId().equals(expected.lastSliceId())
                && actual.planHash().equals(expected.planHash());
    }

    private List<ExportProgress> validateProgress(List<ExportProgress> source) {
        List<ExportProgress> progress = List.copyOf(Objects.requireNonNull(source, "progress"));
        if (progress.isEmpty()) {
            throw new IllegalArgumentException("Terminal export progress must not be empty");
        }
        if (new HashSet<>(progress.stream().map(ExportProgress::artifactName).toList()).size() != progress.size()) {
            throw new IllegalArgumentException("Terminal export progress artifacts must be unique");
        }
        return progress;
    }

    private void requireTransition(ExportRunStatus expected, ExportRunStatus next) {
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(next, "next");
        if (!expected.canTransitionTo(next)) {
            throw new IllegalArgumentException("Invalid export transition: " + expected + " -> " + next);
        }
    }

    private void requireFinish(ExportRunStatus expected, ExportRunStatus terminal) {
        requireTransition(expected, terminal);
        if (terminal != ExportRunStatus.COMPLETED && terminal != ExportRunStatus.SKIPPED) {
            throw new IllegalArgumentException("Export finish requires COMPLETED or SKIPPED");
        }
    }

    private void validateTransitionData(ExportRunStatus next,
                                        String manifestSha256,
                                        String reason) {
        if (manifestSha256 != null && !SHA256.matcher(manifestSha256).matches()) {
            throw new IllegalArgumentException("manifestSha256 must be a lower-case SHA-256 value");
        }
        if (next == ExportRunStatus.STAGED && manifestSha256 == null) {
            throw new IllegalArgumentException("STAGED transition requires manifestSha256");
        }
        if (next == ExportRunStatus.FAILED) {
            if (reason == null || reason.isBlank()) {
                throw new IllegalArgumentException("FAILED transition requires a reason");
            }
        } else if (reason != null) {
            throw new IllegalArgumentException("Only FAILED transition accepts a reason");
        }
    }

    private boolean reachedOrPassed(ExportRunStatus actual, ExportRunStatus requested) {
        if (actual == requested) {
            return true;
        }
        return switch (requested) {
            case STARTED -> false;
            case STAGED -> actual == ExportRunStatus.AVAILABLE || actual == ExportRunStatus.COMPLETED;
            case AVAILABLE -> actual == ExportRunStatus.COMPLETED;
            case COMPLETED, SKIPPED, FAILED -> false;
        };
    }

    private boolean compatibleManifest(ExportRun actual, String requestedManifest) {
        return requestedManifest == null
                || actual.manifestSha256() == null
                || actual.manifestSha256().equals(requestedManifest);
    }

    private boolean compatibleReason(ExportRun actual,
                                     ExportRunStatus requested,
                                     String requestedReason) {
        return requested != ExportRunStatus.FAILED || Objects.equals(actual.reason(), requestedReason);
    }

    private boolean sameStart(ExportRun existing, ExportRun candidate) {
        return existing.status() == ExportRunStatus.STARTED
                && existing.profile().equals(candidate.profile())
                && existing.sliceName().equals(candidate.sliceName())
                && existing.planHash().equals(candidate.planHash())
                && existing.startedAt().equals(candidate.startedAt());
    }

    private DiagnosticException conflict(String runId,
                                         ExportRunStatus expected,
                                         ExportRunStatus next,
                                         String actual) {
        Diagnostic diagnostic = diagnosticFactory.create(ExportDiagnosticCodes.STATE_TRANSITION_CONFLICT)
                .with("runId", runId)
                .with("expectedStatus", expected.name())
                .with("nextStatus", next.name())
                .with("actualStatus", actual)
                .build();
        diagnosticSink.emit(diagnostic);
        return new DiagnosticException(diagnostic);
    }

    private static ExportRun mapRun(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new ExportRun(
                rs.getString("run_id"),
                rs.getString("profile"),
                ExportRunStatus.valueOf(rs.getString("status")),
                rs.getString("slice_name"),
                rs.getString("plan_hash"),
                rs.getString("manifest_sha256"),
                Instant.parse(rs.getString("started_at")),
                Instant.parse(rs.getString("updated_at")),
                rs.getString("reason"));
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
