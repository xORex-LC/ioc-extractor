package com.iocextractor.adapter.out.store.jdbc;

import com.iocextractor.application.dataframeimport.model.ImportDeliverySequence;
import com.iocextractor.application.dataframeimport.model.ImportDeliveryState;
import com.iocextractor.application.dataframeimport.model.ImportDeliveryStatus;
import com.iocextractor.application.port.out.dataframeimport.ImportStatusReader;
import org.springframework.jdbc.core.simple.JdbcClient;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;

/** Indexed value-free service-ledger status reader. */
public final class JdbcImportStatusReader implements ImportStatusReader {

    private final JdbcClient jdbc;
    private final Clock clock;
    private final BooleanSupplier recoveryComplete;

    /** Creates a status reader with externally owned startup-barrier state. */
    public JdbcImportStatusReader(DataSource dataSource,
                                  Clock clock,
                                  BooleanSupplier recoveryComplete) {
        this.jdbc = JdbcClient.create(Objects.requireNonNull(dataSource, "dataSource"));
        this.clock = Objects.requireNonNull(clock, "clock");
        this.recoveryComplete = Objects.requireNonNull(recoveryComplete, "recoveryComplete");
    }

    @Override
    public ImportDeliveryStatus readStatus() {
        Map<ImportDeliveryState, Long> counts = new EnumMap<>(ImportDeliveryState.class);
        jdbc.sql("""
                        SELECT state, COUNT(*) AS state_count
                        FROM import_delivery
                        WHERE state <> 'TERMINAL'
                        GROUP BY state
                        """)
                .query((row, ignored) -> Map.entry(
                        ImportDeliveryState.valueOf(row.getString("state")),
                        row.getLong("state_count")))
                .list()
                .forEach(entry -> counts.put(entry.getKey(), entry.getValue()));
        Optional<Head> head = jdbc.sql("""
                        SELECT sequence_no, state, attempt_count, next_attempt_at_ms,
                               last_error_code, created_at_ms
                        FROM import_delivery
                        WHERE state <> 'TERMINAL'
                        ORDER BY sequence_no
                        LIMIT 1
                        """)
                .query((row, ignored) -> new Head(
                        new ImportDeliverySequence(row.getLong("sequence_no")),
                        ImportDeliveryState.valueOf(row.getString("state")),
                        row.getInt("attempt_count"),
                        Optional.ofNullable(row.getObject("next_attempt_at_ms", Long.class))
                                .map(Instant::ofEpochMilli),
                        Optional.ofNullable(row.getString("last_error_code")),
                        Instant.ofEpochMilli(row.getLong("created_at_ms"))))
                .optional();
        Instant now = clock.instant();
        return new ImportDeliveryStatus(
                counts,
                head.map(Head::sequence),
                head.map(Head::state),
                head.map(value -> nonNegative(Duration.between(value.createdAt(), now))),
                head.map(Head::attemptCount).orElse(0),
                head.flatMap(Head::nextAttemptAt)
                        .map(value -> nonNegative(Duration.between(now, value))),
                head.flatMap(Head::lastErrorCode),
                recoveryComplete.getAsBoolean());
    }

    private Duration nonNegative(Duration duration) {
        return duration.isNegative() ? Duration.ZERO : duration;
    }

    private record Head(ImportDeliverySequence sequence,
                        ImportDeliveryState state,
                        int attemptCount,
                        Optional<Instant> nextAttemptAt,
                        Optional<String> lastErrorCode,
                        Instant createdAt) {
    }
}
