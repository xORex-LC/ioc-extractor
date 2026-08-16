package com.iocextractor.adapter.out.store.jdbc;

import com.iocextractor.application.artifact.lifecycle.EffectiveTime;
import com.iocextractor.application.artifact.lifecycle.LifecycleActivationState;
import com.iocextractor.application.artifact.lifecycle.LifecycleControlState;
import com.iocextractor.application.port.out.artifact.lifecycle.LifecycleControlStore;
import com.iocextractor.common.IocExtractorException;
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

/** SQLite-backed compare-and-set store for the one-way lifecycle activation state. */
public final class JdbcLifecycleControlStore implements LifecycleControlStore {

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final JdbcLifecycleMetadataInspector metadataInspector;
    private final List<String> artifacts;

    /**
     * Creates the store with the complete configured artifact catalog used by
     * the final activation invariant scan.
     */
    public JdbcLifecycleControlStore(DataSource dataSource, List<DataframeArtifactSchema> schemas) {
        Objects.requireNonNull(dataSource, "dataSource");
        this.jdbc = JdbcClient.create(dataSource);
        this.transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        this.metadataInspector = new JdbcLifecycleMetadataInspector(dataSource);
        this.artifacts = Objects.requireNonNull(schemas, "schemas").stream()
                .map(DataframeArtifactSchema::artifactName)
                .toList();
    }

    @Override
    public LifecycleControlState load() {
        try {
            return jdbc.sql("""
                            SELECT version, state, policy_fingerprint, activated_at_ms
                            FROM canonical_lifecycle_control
                            WHERE singleton_id = 1
                            """)
                    .query((resultSet, rowNumber) -> new LifecycleControlState(
                            resultSet.getLong("version"),
                            LifecycleActivationState.valueOf(resultSet.getString("state")),
                            Optional.ofNullable(resultSet.getString("policy_fingerprint")),
                            optionalTime(resultSet, "activated_at_ms")))
                    .single();
        } catch (RuntimeException e) {
            throw new IocExtractorException("Failed to load lifecycle control state", e);
        }
    }

    @Override
    public boolean compareAndSet(LifecycleControlState expected, LifecycleControlState update) {
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(update, "update");
        requireNextLegalState(expected, update);
        try {
            return Boolean.TRUE.equals(transactions.execute(status -> compareAndSetInTransaction(expected, update)));
        } catch (IocExtractorException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new IocExtractorException("Failed to update lifecycle control state", e);
        }
    }

    private boolean compareAndSetInTransaction(LifecycleControlState expected,
                                               LifecycleControlState update) {
        if (!acquireWriteOwnership(expected)) {
            return false;
        }
        if (update.activationState() == LifecycleActivationState.ACTIVE) {
            artifacts.forEach(metadataInspector::requireActivationReady);
        }
        return jdbc.sql("""
                        UPDATE canonical_lifecycle_control
                        SET version = :updateVersion,
                            state = :updateState,
                            policy_fingerprint = :updatePolicy,
                            activated_at_ms = :updateActivatedAt
                        WHERE singleton_id = 1
                          AND version = :expectedVersion
                          AND state = :expectedState
                          AND policy_fingerprint IS :expectedPolicy
                          AND activated_at_ms IS :expectedActivatedAt
                        """)
                .param("updateVersion", update.version())
                .param("updateState", update.activationState().name())
                .param("updatePolicy", update.policyFingerprint().orElse(null))
                .param("updateActivatedAt", epochMillis(update.activatedAt()))
                .param("expectedVersion", expected.version())
                .param("expectedState", expected.activationState().name())
                .param("expectedPolicy", expected.policyFingerprint().orElse(null))
                .param("expectedActivatedAt", epochMillis(expected.activatedAt()))
                .update() == 1;
    }

    private boolean acquireWriteOwnership(LifecycleControlState expected) {
        return jdbc.sql("""
                        UPDATE canonical_lifecycle_control
                        SET version = version
                        WHERE singleton_id = 1
                          AND version = :expectedVersion
                          AND state = :expectedState
                          AND policy_fingerprint IS :expectedPolicy
                          AND activated_at_ms IS :expectedActivatedAt
                        """)
                .param("expectedVersion", expected.version())
                .param("expectedState", expected.activationState().name())
                .param("expectedPolicy", expected.policyFingerprint().orElse(null))
                .param("expectedActivatedAt", epochMillis(expected.activatedAt()))
                .update() == 1;
    }

    private void requireNextLegalState(LifecycleControlState expected, LifecycleControlState update) {
        LifecycleControlState legal = switch (expected.activationState()) {
            case DISABLED_COMPATIBLE -> expected.beginActivation(
                    update.policyFingerprint().orElseThrow(
                            () -> new IllegalArgumentException("Activation requires a policy fingerprint")));
            case ACTIVATING -> expected.completeActivation(
                    update.activatedAt().orElseThrow(
                            () -> new IllegalArgumentException("Activation completion requires a time")));
            case ACTIVE -> throw new IllegalArgumentException("Active lifecycle state is terminal");
        };
        if (!legal.equals(update)) {
            throw new IllegalArgumentException("Lifecycle control update is not the next legal state");
        }
    }

    private Optional<EffectiveTime> optionalTime(ResultSet resultSet, String column) throws SQLException {
        long epochMillis = resultSet.getLong(column);
        return resultSet.wasNull()
                ? Optional.empty()
                : Optional.of(EffectiveTime.at(Instant.ofEpochMilli(epochMillis)));
    }

    private Long epochMillis(Optional<EffectiveTime> time) {
        return time.map(value -> value.value().toEpochMilli()).orElse(null);
    }
}
