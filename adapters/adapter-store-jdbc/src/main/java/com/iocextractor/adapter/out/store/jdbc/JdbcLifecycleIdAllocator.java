package com.iocextractor.adapter.out.store.jdbc;

import com.iocextractor.common.IocExtractorException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.time.Clock;
import java.util.Objects;

/**
 * Atomically reserves committed, never-returned lifecycle-id ranges.
 * Reservations commit independently and must precede canonical SQLite write ownership.
 */
final class JdbcLifecycleIdAllocator {

    private final JdbcClient jdbc;
    private final Clock clock;
    private final TransactionTemplate committedTransactions;

    JdbcLifecycleIdAllocator(DataSource dataSource, Clock clock) {
        Objects.requireNonNull(dataSource, "dataSource");
        this.jdbc = JdbcClient.create(dataSource);
        this.clock = Objects.requireNonNull(clock, "clock");
        this.committedTransactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        this.committedTransactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    LifecycleIdReservation reserve(int count) {
        if (count < 0) {
            throw new IllegalArgumentException("Lifecycle reservation count must not be negative");
        }
        long largestStart = Long.MAX_VALUE - count;
        try {
            long next = Objects.requireNonNull(committedTransactions.execute(status ->
                    jdbc.sql("""
                                    UPDATE lifecycle_id_allocator
                                    SET next_value = next_value + :count,
                                        updated_at_ms = :updatedAt
                                    WHERE singleton_id = 1
                                      AND next_value <= :largestStart
                                    RETURNING next_value
                                    """)
                            .param("count", count)
                            .param("updatedAt", clock.millis())
                            .param("largestStart", largestStart)
                            .query(Long.class)
                            .optional()
                            .orElseThrow(() -> new IocExtractorException(
                                    "Lifecycle id space is exhausted"))));
            return new LifecycleIdReservation(Math.subtractExact(next, count), count);
        } catch (IocExtractorException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new IocExtractorException("Failed to reserve lifecycle ids", e);
        }
    }
}
