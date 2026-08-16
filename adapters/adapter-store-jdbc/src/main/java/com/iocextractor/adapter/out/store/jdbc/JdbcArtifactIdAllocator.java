package com.iocextractor.adapter.out.store.jdbc;

import com.iocextractor.application.artifact.ArtifactIdReservation;
import com.iocextractor.application.artifact.ArtifactIdStrategy;
import com.iocextractor.common.IocExtractorException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.time.Clock;
import java.util.Objects;
import java.util.OptionalLong;

/**
 * Durable direction-aware allocator for one independent public artifact ID space.
 * Reservations commit independently and must precede canonical SQLite write ownership.
 */
final class JdbcArtifactIdAllocator {

    private final JdbcClient jdbc;
    private final Clock clock;
    private final TransactionTemplate committedTransactions;

    JdbcArtifactIdAllocator(DataSource dataSource, Clock clock) {
        Objects.requireNonNull(dataSource, "dataSource");
        this.jdbc = JdbcClient.create(dataSource);
        this.clock = Objects.requireNonNull(clock, "clock");
        this.committedTransactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        this.committedTransactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    void ensureInitialized(ArtifactIdAllocatorDefinition definition) {
        Objects.requireNonNull(definition, "definition");
        try {
            long safeNext = safeInitialValue(definition);
            jdbc.sql("""
                            INSERT INTO artifact_id_allocator(
                                artifact, strategy, next_value, identity_epoch, updated_at_ms)
                            VALUES (:artifact, :strategy, :nextValue, :identityEpoch, :updatedAt)
                            ON CONFLICT(artifact) DO NOTHING
                            """)
                    .param("artifact", definition.artifact())
                    .param("strategy", definition.strategy().name())
                    .param("nextValue", safeNext)
                    .param("identityEpoch", definition.identityEpoch())
                    .param("updatedAt", clock.millis())
                    .update();
            AllocatorState state = load(definition.artifact());
            if (state.strategy() != definition.strategy()) {
                throw new IocExtractorException("Public id allocator identity drift for artifact: "
                        + definition.artifact());
            }
            alignIdentityEpoch(definition, state);
        } catch (IocExtractorException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new IocExtractorException(
                    "Failed to initialize public id allocator for artifact: " + definition.artifact(), e);
        }
    }

    ArtifactIdReservation reserve(String artifact, int count) {
        String validatedArtifact = DataframeColumn.requireSqlIdentifier(artifact, "artifact name");
        if (count < 0) {
            throw new IllegalArgumentException("Public id reservation count must not be negative");
        }
        AllocatorState state = load(validatedArtifact);
        try {
            return Objects.requireNonNull(committedTransactions.execute(
                    status -> reserveInTransaction(validatedArtifact, count, state.strategy())));
        } catch (IocExtractorException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new IocExtractorException(
                    "Failed to reserve public ids for artifact: " + validatedArtifact, e);
        }
    }

    private ArtifactIdReservation reserveInTransaction(String artifact,
                                                        int count,
                                                        ArtifactIdStrategy strategy) {
        long delta = strategy == ArtifactIdStrategy.ASCENDING ? count : -(long) count;
        long boundary = strategy == ArtifactIdStrategy.ASCENDING
                ? Long.MAX_VALUE - count
                : Long.MIN_VALUE + count;
        long next = jdbc.sql(reservationSql(strategy))
                .param("delta", delta)
                .param("updatedAt", clock.millis())
                .param("artifact", artifact)
                .param("strategy", strategy.name())
                .param("boundary", boundary)
                .query(Long.class)
                .optional()
                .orElseThrow(() -> new IocExtractorException(
                        "Public id space is exhausted for artifact: " + artifact));
        return new ArtifactIdReservation(Math.subtractExact(next, delta), count, strategy);
    }

    private String reservationSql(ArtifactIdStrategy strategy) {
        if (strategy == ArtifactIdStrategy.ASCENDING) {
            return """
                    UPDATE artifact_id_allocator
                    SET next_value = next_value + :delta,
                        updated_at_ms = :updatedAt
                    WHERE artifact = :artifact
                      AND strategy = :strategy
                      AND next_value <= :boundary
                    RETURNING next_value
                    """;
        }
        return """
                UPDATE artifact_id_allocator
                SET next_value = next_value + :delta,
                    updated_at_ms = :updatedAt
                WHERE artifact = :artifact
                  AND strategy = :strategy
                  AND next_value >= :boundary
                RETURNING next_value
                """;
    }

    private long safeInitialValue(ArtifactIdAllocatorDefinition definition) {
        OptionalLong storedExtreme = storedExtreme(definition.artifact(), definition.strategy());
        if (storedExtreme.isEmpty()) {
            return definition.configuredNextValue();
        }
        long nextAfterStored = definition.strategy() == ArtifactIdStrategy.ASCENDING
                ? Math.incrementExact(storedExtreme.getAsLong())
                : Math.decrementExact(storedExtreme.getAsLong());
        return definition.strategy() == ArtifactIdStrategy.ASCENDING
                ? Math.max(definition.configuredNextValue(), nextAfterStored)
                : Math.min(definition.configuredNextValue(), nextAfterStored);
    }

    private OptionalLong storedExtreme(String artifact, ArtifactIdStrategy strategy) {
        String aggregate = strategy == ArtifactIdStrategy.ASCENDING ? "MAX" : "MIN";
        String active = quote(artifact);
        String history = quote(artifact + "_history");
        return jdbc.sql("SELECT " + aggregate + "(" + quote("id") + ") FROM ("
                        + "SELECT " + quote("id") + " FROM " + active
                        + " UNION ALL SELECT " + quote("id") + " FROM " + history + ")")
                .query(Long.class)
                .optional()
                .stream()
                .mapToLong(Long::longValue)
                .findFirst();
    }

    private void alignIdentityEpoch(ArtifactIdAllocatorDefinition definition, AllocatorState state) {
        if (state.identityEpoch() > definition.identityEpoch()) {
            throw new IocExtractorException(
                    "Public id allocator identity epoch is newer than configured for artifact: "
                            + definition.artifact());
        }
        if (state.identityEpoch() == definition.identityEpoch()) {
            return;
        }
        jdbc.sql("""
                        UPDATE artifact_id_allocator
                        SET identity_epoch = :updateEpoch,
                            updated_at_ms = :updatedAt
                        WHERE artifact = :artifact
                          AND strategy = :strategy
                          AND identity_epoch = :expectedEpoch
                        """)
                .param("updateEpoch", definition.identityEpoch())
                .param("updatedAt", clock.millis())
                .param("artifact", definition.artifact())
                .param("strategy", definition.strategy().name())
                .param("expectedEpoch", state.identityEpoch())
                .update();
        AllocatorState current = load(definition.artifact());
        if (current.strategy() != definition.strategy()
                || current.identityEpoch() != definition.identityEpoch()) {
            throw new IocExtractorException(
                    "Public id allocator identity epoch changed concurrently for artifact: "
                            + definition.artifact());
        }
    }

    private AllocatorState load(String artifact) {
        return jdbc.sql("""
                        SELECT strategy, identity_epoch
                        FROM artifact_id_allocator
                        WHERE artifact = :artifact
                        """)
                .param("artifact", artifact)
                .query((resultSet, rowNumber) -> new AllocatorState(
                        ArtifactIdStrategy.valueOf(resultSet.getString("strategy")),
                        resultSet.getLong("identity_epoch")))
                .optional()
                .orElseThrow(() -> new IocExtractorException(
                        "Public id allocator is not initialized for artifact: " + artifact));
    }

    private String quote(String identifier) {
        return "\"" + DataframeColumn.requireSqlIdentifier(identifier, "identifier") + "\"";
    }

    private record AllocatorState(ArtifactIdStrategy strategy, long identityEpoch) {
    }
}
