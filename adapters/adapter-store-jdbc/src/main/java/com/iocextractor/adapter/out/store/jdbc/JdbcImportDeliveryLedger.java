package com.iocextractor.adapter.out.store.jdbc;

import com.iocextractor.application.dataframeimport.model.ImportClaimReservation;
import com.iocextractor.application.dataframeimport.model.ImportContractFingerprint;
import com.iocextractor.application.dataframeimport.model.ImportContractId;
import com.iocextractor.application.dataframeimport.model.ImportContractPin;
import com.iocextractor.application.dataframeimport.model.ImportDelivery;
import com.iocextractor.application.dataframeimport.model.ImportDeliveryCheckpoint;
import com.iocextractor.application.dataframeimport.model.ImportDeliveryEvidence;
import com.iocextractor.application.dataframeimport.model.ImportDeliveryId;
import com.iocextractor.application.dataframeimport.model.ImportDeliverySequence;
import com.iocextractor.application.dataframeimport.model.ImportDeliveryState;
import com.iocextractor.application.dataframeimport.model.ImportDeliveryRetryState;
import com.iocextractor.application.dataframeimport.model.ImportDeliveryTransition;
import com.iocextractor.application.dataframeimport.model.ImportLedgerTransitionResult;
import com.iocextractor.application.dataframeimport.model.ImportRetrySchedule;
import com.iocextractor.application.dataframeimport.model.ImportSha256;
import com.iocextractor.application.dataframeimport.model.ImportSnapshot;
import com.iocextractor.application.dataframeimport.model.ImportSnapshotReference;
import com.iocextractor.application.dataframeimport.model.ImportSourceId;
import com.iocextractor.application.dataframeimport.model.ImportStage;
import com.iocextractor.application.dataframeimport.model.ImportStageReference;
import com.iocextractor.application.dataframeimport.model.ImportTerminalOutcome;
import com.iocextractor.application.port.out.dataframeimport.ImportDeliveryLedger;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * SQLite service-ledger adapter for globally ordered managed dataframe imports.
 * Reservation, state/version CAS and compact transition evidence are committed
 * atomically. The minimum nonterminal sequence remains the sole scheduling head.
 */
public final class JdbcImportDeliveryLedger implements ImportDeliveryLedger {

    private static final String SELECT_COLUMNS = """
            sequence_no, delivery_id, source_id, candidate_token, replay_of,
            state, terminal_outcome, version,
            snapshot_locator, snapshot_sha256, snapshot_size,
            contract_id, contract_version, contract_fingerprint,
            stage_locator, stage_sha256, stage_source_rows,
            stage_accepted_rows, stage_rejected_rows,
            attempt_count, next_attempt_at_ms, last_error_code,
            created_at_ms, updated_at_ms
            """;
    private static final String FIND_HEAD_SQL = "SELECT " + SELECT_COLUMNS + """
            FROM import_delivery
            WHERE state <> 'TERMINAL'
            ORDER BY sequence_no
            LIMIT 1
            """;
    private static final String FIND_DUE_HEAD_SQL = "SELECT " + SELECT_COLUMNS + " FROM (SELECT "
            + SELECT_COLUMNS + """
            FROM import_delivery
            WHERE state <> 'TERMINAL'
            ORDER BY sequence_no
            LIMIT 1)
            WHERE next_attempt_at_ms IS NULL OR next_attempt_at_ms <= :now
            """;
    private static final String FIND_RECOVERABLE_SQL = "SELECT " + SELECT_COLUMNS + """
            FROM import_delivery
            WHERE state <> 'TERMINAL'
            ORDER BY sequence_no
            LIMIT :limit
            """;

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;

    /** Creates a ledger on an already migrated service datasource. */
    public JdbcImportDeliveryLedger(DataSource dataSource) {
        Objects.requireNonNull(dataSource, "dataSource");
        this.jdbc = JdbcClient.create(dataSource);
        this.transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    @Override
    public ImportDelivery reserveClaim(ImportClaimReservation reservation) {
        Objects.requireNonNull(reservation, "reservation");
        try {
            ImportDelivery result = transactions.execute(status -> {
                int affected = jdbc.sql("""
                                INSERT INTO import_delivery(
                                    delivery_id, source_id, candidate_token, replay_of,
                                    state, terminal_outcome, version, attempt_count,
                                    created_at_ms, updated_at_ms)
                                VALUES (
                                    :delivery_id, :source_id, :candidate_token, :replay_of,
                                    'DETECTED', NULL, 0, 0, :created_at_ms, :updated_at_ms)
                                ON CONFLICT(delivery_id) DO NOTHING
                                """)
                        .param("delivery_id", reservation.deliveryId().value())
                        .param("source_id", reservation.sourceId().value())
                        .param("candidate_token", reservation.candidateToken())
                        .param("replay_of", reservation.replayOf().map(ImportDeliveryId::value).orElse(null))
                        .param("created_at_ms", reservation.detectedAt().toEpochMilli())
                        .param("updated_at_ms", reservation.detectedAt().toEpochMilli())
                        .update();
                ImportDelivery delivery = required(reservation.deliveryId());
                if (!sameReservation(delivery, reservation)) {
                    throw new IllegalStateException("Import delivery identity conflicts with an existing reservation");
                }
                if (affected == 1) {
                    appendTransition(delivery.id(), ImportDeliveryState.DETECTED,
                            ImportDeliveryState.DETECTED, "IMPORT.CLAIM_RESERVED", reservation.detectedAt());
                }
                return delivery;
            });
            return Objects.requireNonNull(result, "transaction result");
        } catch (DataAccessException failure) {
            throw new IllegalStateException("Import candidate already has an active delivery", failure);
        }
    }

    @Override
    public Optional<ImportDelivery> find(ImportDeliveryId deliveryId) {
        Objects.requireNonNull(deliveryId, "deliveryId");
        return jdbc.sql("SELECT " + SELECT_COLUMNS + " FROM import_delivery WHERE delivery_id = :delivery_id")
                .param("delivery_id", deliveryId.value())
                .query(JdbcImportDeliveryLedger::mapDelivery)
                .optional();
    }

    @Override
    public Optional<ImportDelivery> findHead() {
        return jdbc.sql(FIND_HEAD_SQL)
                .query(JdbcImportDeliveryLedger::mapDelivery)
                .optional();
    }

    @Override
    public Optional<ImportDelivery> findDueHead(Instant now) {
        Objects.requireNonNull(now, "now");
        return jdbc.sql(FIND_DUE_HEAD_SQL)
                .param("now", now.toEpochMilli())
                .query(JdbcImportDeliveryLedger::mapDelivery)
                .optional();
    }

    @Override
    public ImportLedgerTransitionResult transition(ImportDeliveryTransition transition) {
        Objects.requireNonNull(transition, "transition");
        requireLegalTransition(transition);
        ImportLedgerTransitionResult result = transactions.execute(status -> {
            int affected = updateTransition(transition);
            if (affected == 1) {
                appendTransition(transition.deliveryId(), transition.expectedState(), transition.nextState(),
                        transition.safeCode().orElse(null), transition.occurredAt());
                return ImportLedgerTransitionResult.APPLIED;
            }
            return resolveTransitionReplay(transition);
        });
        return Objects.requireNonNull(result, "transaction result");
    }

    @Override
    public ImportLedgerTransitionResult scheduleRetry(ImportRetrySchedule schedule) {
        Objects.requireNonNull(schedule, "schedule");
        ImportLedgerTransitionResult result = transactions.execute(status -> {
            int affected = jdbc.sql("""
                            UPDATE import_delivery
                            SET version = version + 1,
                                attempt_count = attempt_count + :attempt_increment,
                                next_attempt_at_ms = :next_attempt_at_ms,
                                last_error_code = :last_error_code,
                                updated_at_ms = :updated_at_ms
                            WHERE delivery_id = :delivery_id
                              AND state = :expected_state
                              AND state <> 'TERMINAL'
                              AND version = :expected_version
                            """)
                    .param("attempt_increment", schedule.failedAttempt() ? 1 : 0)
                    .param("next_attempt_at_ms", schedule.nextAttemptAt().toEpochMilli())
                    .param("last_error_code", schedule.safeCode())
                    .param("updated_at_ms", schedule.occurredAt().toEpochMilli())
                    .param("delivery_id", schedule.deliveryId().value())
                    .param("expected_state", schedule.expectedState().name())
                    .param("expected_version", schedule.expectedVersion())
                    .update();
            if (affected == 1) {
                appendTransition(schedule.deliveryId(), schedule.expectedState(), schedule.expectedState(),
                        schedule.safeCode(), schedule.occurredAt());
                return ImportLedgerTransitionResult.APPLIED;
            }
            return resolveRetryReplay(schedule);
        });
        return Objects.requireNonNull(result, "transaction result");
    }

    @Override
    public List<ImportDelivery> findRecoverable(int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("Import recovery limit must be positive");
        }
        return jdbc.sql(FIND_RECOVERABLE_SQL)
                .param("limit", limit)
                .query(JdbcImportDeliveryLedger::mapDelivery)
                .list();
    }

    private int updateTransition(ImportDeliveryTransition transition) {
        ImportDeliveryCheckpoint checkpoint = transition.checkpoint();
        ImportSnapshot snapshot = checkpoint.snapshot().orElse(null);
        ImportContractPin contract = checkpoint.contract().orElse(null);
        ImportStage stage = checkpoint.stage().orElse(null);
        return jdbc.sql("""
                        UPDATE import_delivery
                        SET state = :next_state,
                            version = version + 1,
                            terminal_outcome = :terminal_outcome,
                            snapshot_locator = COALESCE(:snapshot_locator, snapshot_locator),
                            snapshot_sha256 = COALESCE(:snapshot_sha256, snapshot_sha256),
                            snapshot_size = COALESCE(:snapshot_size, snapshot_size),
                            contract_id = COALESCE(:contract_id, contract_id),
                            contract_version = COALESCE(:contract_version, contract_version),
                            contract_fingerprint = COALESCE(:contract_fingerprint, contract_fingerprint),
                            stage_locator = COALESCE(:stage_locator, stage_locator),
                            stage_sha256 = COALESCE(:stage_sha256, stage_sha256),
                            stage_source_rows = COALESCE(:stage_source_rows, stage_source_rows),
                            stage_accepted_rows = COALESCE(:stage_accepted_rows, stage_accepted_rows),
                            stage_rejected_rows = COALESCE(:stage_rejected_rows, stage_rejected_rows),
                            next_attempt_at_ms = NULL,
                            last_error_code = :last_error_code,
                            updated_at_ms = :updated_at_ms,
                            terminal_at_ms = CASE WHEN :next_state = 'TERMINAL' THEN :updated_at_ms ELSE NULL END
                        WHERE delivery_id = :delivery_id
                          AND state = :expected_state
                          AND version = :expected_version
                        """)
                .param("next_state", transition.nextState().name())
                .param("terminal_outcome", transition.terminalOutcome()
                        .map(ImportTerminalOutcome::name).orElse(null))
                .param("snapshot_locator", snapshot == null ? null : snapshot.reference().value())
                .param("snapshot_sha256", snapshot == null ? null : snapshot.digest().value())
                .param("snapshot_size", snapshot == null ? null : snapshot.size())
                .param("contract_id", contract == null ? null : contract.id().value())
                .param("contract_version", contract == null ? null : contract.version())
                .param("contract_fingerprint", contract == null ? null : contract.fingerprint().value())
                .param("stage_locator", stage == null ? null : stage.reference().value())
                .param("stage_sha256", stage == null ? null : stage.digest().value())
                .param("stage_source_rows", stage == null ? null : stage.sourceRows())
                .param("stage_accepted_rows", stage == null ? null : stage.acceptedRows())
                .param("stage_rejected_rows", stage == null ? null : stage.rejectedRows())
                .param("last_error_code", transition.safeCode().orElse(null))
                .param("updated_at_ms", transition.occurredAt().toEpochMilli())
                .param("delivery_id", transition.deliveryId().value())
                .param("expected_state", transition.expectedState().name())
                .param("expected_version", transition.expectedVersion())
                .update();
    }

    private ImportLedgerTransitionResult resolveTransitionReplay(ImportDeliveryTransition transition) {
        Optional<ImportDelivery> current = find(transition.deliveryId());
        if (current.isEmpty()) {
            return ImportLedgerTransitionResult.MISSING;
        }
        ImportDelivery delivery = current.orElseThrow();
        return delivery.state() == transition.nextState()
                && delivery.version() == transition.expectedVersion() + 1
                && delivery.terminalOutcome().equals(transition.terminalOutcome())
                && checkpointMatches(delivery, transition.checkpoint())
                ? ImportLedgerTransitionResult.ALREADY_APPLIED
                : ImportLedgerTransitionResult.CONFLICT;
    }

    private ImportLedgerTransitionResult resolveRetryReplay(ImportRetrySchedule schedule) {
        Optional<ImportDelivery> current = find(schedule.deliveryId());
        if (current.isEmpty()) {
            return ImportLedgerTransitionResult.MISSING;
        }
        ImportDelivery delivery = current.orElseThrow();
        return delivery.state() == schedule.expectedState()
                && delivery.version() == schedule.expectedVersion() + 1
                && delivery.nextAttemptAt().filter(schedule.nextAttemptAt()::equals).isPresent()
                && delivery.lastErrorCode().filter(schedule.safeCode()::equals).isPresent()
                ? ImportLedgerTransitionResult.ALREADY_APPLIED
                : ImportLedgerTransitionResult.CONFLICT;
    }

    private boolean checkpointMatches(ImportDelivery delivery, ImportDeliveryCheckpoint checkpoint) {
        return checkpoint.snapshot().map(value -> delivery.snapshot().filter(value::equals).isPresent()).orElse(true)
                && checkpoint.contract().map(value -> delivery.contract().filter(value::equals).isPresent()).orElse(true)
                && checkpoint.stage().map(value -> delivery.stage().filter(value::equals).isPresent()).orElse(true);
    }

    private void appendTransition(ImportDeliveryId deliveryId,
                                  ImportDeliveryState from,
                                  ImportDeliveryState to,
                                  String safeCode,
                                  Instant occurredAt) {
        jdbc.sql("""
                        INSERT INTO import_delivery_transition(
                            delivery_id, ordinal, from_state, to_state, safe_code, occurred_at_ms)
                        SELECT :delivery_id,
                               COALESCE(MAX(ordinal), 0) + 1,
                               :from_state, :to_state, :safe_code, :occurred_at_ms
                        FROM import_delivery_transition
                        WHERE delivery_id = :delivery_id
                        """)
                .param("delivery_id", deliveryId.value())
                .param("from_state", from.name())
                .param("to_state", to.name())
                .param("safe_code", safeCode)
                .param("occurred_at_ms", occurredAt.toEpochMilli())
                .update();
    }

    private ImportDelivery required(ImportDeliveryId deliveryId) {
        return find(deliveryId).orElseThrow(() -> new IllegalStateException(
                "Import delivery disappeared during its service-ledger transaction"));
    }

    private boolean sameReservation(ImportDelivery delivery, ImportClaimReservation reservation) {
        return delivery.id().equals(reservation.deliveryId())
                && delivery.sourceId().equals(reservation.sourceId())
                && delivery.candidateToken().equals(reservation.candidateToken())
                && delivery.replayOf().equals(reservation.replayOf());
    }

    private void requireLegalTransition(ImportDeliveryTransition transition) {
        ImportDeliveryState expected = transition.expectedState();
        ImportDeliveryState next = transition.nextState();
        if (expected == ImportDeliveryState.TERMINAL) {
            throw new IllegalArgumentException("Terminal import delivery cannot transition");
        }
        if (next != ImportDeliveryState.TERMINAL && next.ordinal() != expected.ordinal() + 1) {
            throw new IllegalArgumentException("Import delivery transition must advance exactly one state");
        }
        if (next == ImportDeliveryState.TERMINAL) {
            requireTerminalOutcome(expected, transition.terminalOutcome().orElseThrow());
        }
        ImportDeliveryCheckpoint checkpoint = transition.checkpoint();
        boolean validCheckpoint = switch (next) {
            case SNAPSHOT_PINNED -> checkpoint.snapshot().isPresent();
            case CONTRACT_PINNED -> checkpoint.contract().isPresent();
            case STAGED -> checkpoint.stage().isPresent();
            default -> checkpoint.equals(ImportDeliveryCheckpoint.none());
        };
        if (!validCheckpoint) {
            throw new IllegalArgumentException("Import transition checkpoint does not match target state " + next);
        }
    }

    private void requireTerminalOutcome(ImportDeliveryState expected, ImportTerminalOutcome outcome) {
        if (expected.ordinal() < ImportDeliveryState.CANONICAL_COMMITTED.ordinal()
                && outcome != ImportTerminalOutcome.REJECTED) {
            throw new IllegalArgumentException("Pre-commit import delivery may terminate only as REJECTED");
        }
        if (expected.ordinal() >= ImportDeliveryState.CANONICAL_COMMITTED.ordinal()
                && outcome == ImportTerminalOutcome.REJECTED) {
            throw new IllegalArgumentException("Canonically committed import delivery cannot become REJECTED");
        }
    }

    private static ImportDelivery mapDelivery(ResultSet resultSet, int rowNumber) throws SQLException {
        Optional<ImportSnapshot> snapshot = optional(resultSet.getString("snapshot_locator"))
                .map(locator -> new ImportSnapshot(
                        new ImportSnapshotReference(locator),
                        new ImportSha256(resultSetString(resultSet, "snapshot_sha256")),
                        resultSetLong(resultSet, "snapshot_size")));
        Optional<ImportContractPin> contract = optional(resultSet.getString("contract_id"))
                .map(id -> new ImportContractPin(
                        new ImportContractId(id),
                        resultSetInt(resultSet, "contract_version"),
                        new ImportContractFingerprint(resultSetString(resultSet, "contract_fingerprint"))));
        Optional<ImportStage> stage = optional(resultSet.getString("stage_locator"))
                .map(locator -> new ImportStage(
                        new ImportStageReference(locator),
                        new ImportSha256(resultSetString(resultSet, "stage_sha256")),
                        resultSetLong(resultSet, "stage_source_rows"),
                        resultSetLong(resultSet, "stage_accepted_rows"),
                        resultSetLong(resultSet, "stage_rejected_rows")));
        return new ImportDelivery(
                new ImportDeliveryId(resultSet.getString("delivery_id")),
                new ImportDeliverySequence(resultSet.getLong("sequence_no")),
                new ImportSourceId(resultSet.getString("source_id")),
                resultSet.getString("candidate_token"),
                optional(resultSet.getString("replay_of")).map(ImportDeliveryId::new),
                ImportDeliveryState.valueOf(resultSet.getString("state")),
                resultSet.getLong("version"),
                new ImportDeliveryEvidence(snapshot, contract, stage),
                new ImportDeliveryRetryState(
                        resultSet.getInt("attempt_count"),
                        optionalLong(resultSet, "next_attempt_at_ms").map(Instant::ofEpochMilli),
                        optional(resultSet.getString("last_error_code"))),
                optional(resultSet.getString("terminal_outcome")).map(ImportTerminalOutcome::valueOf),
                Instant.ofEpochMilli(resultSet.getLong("created_at_ms")),
                Instant.ofEpochMilli(resultSet.getLong("updated_at_ms")));
    }

    private static Optional<String> optional(String value) {
        return Optional.ofNullable(value);
    }

    private static Optional<Long> optionalLong(ResultSet resultSet, String column) throws SQLException {
        long value = resultSet.getLong(column);
        return resultSet.wasNull() ? Optional.empty() : Optional.of(value);
    }

    private static String resultSetString(ResultSet resultSet, String column) {
        try {
            return resultSet.getString(column);
        } catch (SQLException failure) {
            throw new IllegalStateException("Cannot read import delivery column " + column, failure);
        }
    }

    private static long resultSetLong(ResultSet resultSet, String column) {
        try {
            return resultSet.getLong(column);
        } catch (SQLException failure) {
            throw new IllegalStateException("Cannot read import delivery column " + column, failure);
        }
    }

    private static int resultSetInt(ResultSet resultSet, String column) {
        try {
            return resultSet.getInt(column);
        } catch (SQLException failure) {
            throw new IllegalStateException("Cannot read import delivery column " + column, failure);
        }
    }
}
