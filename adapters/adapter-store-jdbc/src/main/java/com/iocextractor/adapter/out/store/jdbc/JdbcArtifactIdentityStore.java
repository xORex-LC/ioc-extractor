package com.iocextractor.adapter.out.store.jdbc;

import com.iocextractor.application.aggregation.ArtifactIdentityDefinition;
import com.iocextractor.application.aggregation.StoredArtifactIdentity;
import com.iocextractor.application.port.out.aggregation.ArtifactIdentityStore;
import com.iocextractor.diagnostics.Diagnostic;
import com.iocextractor.diagnostics.DiagnosticException;
import com.iocextractor.diagnostics.DiagnosticFactory;
import com.iocextractor.diagnostics.codes.StorageDiagnosticCodes;
import com.iocextractor.diagnostics.sink.DiagnosticSink;
import com.iocextractor.diagnostics.sink.NoopDiagnosticSink;
import com.iocextractor.observability.EventAction;
import com.iocextractor.observability.EventOutcome;
import com.iocextractor.observability.LogField;
import com.iocextractor.observability.logging.LogEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.time.Clock;
import java.util.Objects;
import java.util.Optional;

/**
 * JDBC-backed guardrail store for artifact identity formula hashes.
 */
public final class JdbcArtifactIdentityStore implements ArtifactIdentityStore {

    private static final Logger LOGGER = LoggerFactory.getLogger(JdbcArtifactIdentityStore.class);

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final DiagnosticSink diagnosticSink;
    private final DiagnosticFactory diagnosticFactory;
    private final Clock clock;
    private final String dbRole;

    public JdbcArtifactIdentityStore(DataSource dataSource, Clock clock) {
        this(dataSource, clock, NoopDiagnosticSink.INSTANCE, new DiagnosticFactory(clock), "dataframe");
    }

    public JdbcArtifactIdentityStore(DataSource dataSource,
                                     Clock clock,
                                     DiagnosticSink diagnosticSink,
                                     DiagnosticFactory diagnosticFactory,
                                     String dbRole) {
        Objects.requireNonNull(dataSource, "dataSource");
        this.jdbc = JdbcClient.create(dataSource);
        this.transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        this.clock = Objects.requireNonNull(clock, "clock");
        this.diagnosticSink = Objects.requireNonNull(diagnosticSink, "diagnosticSink");
        this.diagnosticFactory = Objects.requireNonNull(diagnosticFactory, "diagnosticFactory");
        if (dbRole == null || dbRole.isBlank()) {
            throw new IllegalArgumentException("dbRole is required");
        }
        this.dbRole = dbRole;
    }

    @Override
    public StoredArtifactIdentity ensure(ArtifactIdentityDefinition definition) {
        Objects.requireNonNull(definition, "definition");
        return transactions.execute(status -> ensureInTransaction(definition));
    }

    private StoredArtifactIdentity ensureInTransaction(ArtifactIdentityDefinition definition) {
        Optional<StoredArtifactIdentity> existing = load(definition.artifactName());
        if (existing.isEmpty()) {
            insert(definition);
            return new StoredArtifactIdentity(
                    definition.artifactName(), definition.identityHash(), definition.epoch());
        }

        StoredArtifactIdentity stored = existing.get();
        if (stored.epoch() > definition.epoch()) {
            throw identityDrift(definition, stored, "configured epoch is older than stored epoch");
        }
        if (stored.identityHash().equals(definition.identityHash())) {
            if (definition.epoch() > stored.epoch()) {
                update(definition);
                emitEpochBump(stored, definition);
                return new StoredArtifactIdentity(
                        definition.artifactName(), definition.identityHash(), definition.epoch());
            }
            return stored;
        }
        if (definition.epoch() <= stored.epoch()) {
            throw identityDrift(definition, stored, "identity hash changed without epoch bump");
        }

        update(definition);
        emitEpochBump(stored, definition);
        return new StoredArtifactIdentity(
                definition.artifactName(), definition.identityHash(), definition.epoch());
    }

    private Optional<StoredArtifactIdentity> load(String artifactName) {
        return jdbc.sql("""
                SELECT artifact, identity_hash, epoch
                FROM artifact_identity
                WHERE artifact = :artifact
                """)
                .param("artifact", artifactName)
                .query((rs, rowNum) -> new StoredArtifactIdentity(
                        rs.getString("artifact"),
                        rs.getString("identity_hash"),
                        rs.getInt("epoch")))
                .optional();
    }

    private void insert(ArtifactIdentityDefinition definition) {
        jdbc.sql("""
                INSERT INTO artifact_identity(artifact, identity_hash, epoch, applied_at)
                VALUES (:artifact, :identity_hash, :epoch, :applied_at)
                """)
                .param("artifact", definition.artifactName())
                .param("identity_hash", definition.identityHash())
                .param("epoch", definition.epoch())
                .param("applied_at", clock.instant().toString())
                .update();
    }

    private void update(ArtifactIdentityDefinition definition) {
        jdbc.sql("""
                UPDATE artifact_identity
                SET identity_hash = :identity_hash,
                    epoch = :epoch,
                    applied_at = :applied_at
                WHERE artifact = :artifact
                """)
                .param("artifact", definition.artifactName())
                .param("identity_hash", definition.identityHash())
                .param("epoch", definition.epoch())
                .param("applied_at", clock.instant().toString())
                .update();
    }

    private DiagnosticException identityDrift(ArtifactIdentityDefinition definition,
                                              StoredArtifactIdentity stored,
                                              String reason) {
        Diagnostic diagnostic = diagnosticFactory.create(StorageDiagnosticCodes.IDENTITY_DRIFT)
                .with("artifact", definition.artifactName())
                .with("identityEpoch", stored.epoch())
                .with("reason", reason)
                .build();
        diagnosticSink.emit(diagnostic);
        LogEvents.error(LOGGER)
                .action(EventAction.SCHEMA_VALIDATE)
                .outcome(EventOutcome.FAILURE)
                .field(LogField.IOC_DB_ROLE, dbRole)
                .field(LogField.IOC_ARTIFACT_NAME, definition.artifactName())
                .field(LogField.IOC_IDENTITY_EPOCH, stored.epoch())
                .message("artifact identity drift refused")
                .log();
        return new DiagnosticException(diagnostic);
    }

    private void emitEpochBump(StoredArtifactIdentity stored, ArtifactIdentityDefinition definition) {
        Diagnostic diagnostic = diagnosticFactory.create(StorageDiagnosticCodes.IDENTITY_EPOCH_BUMP)
                .with("artifact", definition.artifactName())
                .with("fromEpoch", stored.epoch())
                .with("toEpoch", definition.epoch())
                .build();
        diagnosticSink.emit(diagnostic);
        LogEvents.info(LOGGER)
                .action(EventAction.BACKFILL)
                .outcome(EventOutcome.SUCCESS)
                .field(LogField.IOC_DB_ROLE, dbRole)
                .field(LogField.IOC_ARTIFACT_NAME, definition.artifactName())
                .field(LogField.IOC_IDENTITY_EPOCH, definition.epoch())
                .message("artifact identity epoch bumped")
                .log();
    }
}
