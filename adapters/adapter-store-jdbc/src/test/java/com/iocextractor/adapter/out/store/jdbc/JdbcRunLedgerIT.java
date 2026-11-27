package com.iocextractor.adapter.out.store.jdbc;

import com.iocextractor.application.tck.junit.IntegrationTest;
import com.iocextractor.application.artifact.DocumentRun;
import com.iocextractor.application.artifact.IngestRunStatus;
import com.iocextractor.application.artifact.lifecycle.ObservationId;
import com.iocextractor.application.ingest.SourceKey;
import com.iocextractor.application.observation.ObservationOrder;
import com.iocextractor.application.observation.ObservationOrigin;
import com.iocextractor.application.observation.RegisteredObservation;
import com.iocextractor.common.IocExtractorException;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@IntegrationTest
class JdbcRunLedgerIT {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-06-25T00:00:00Z"), ZoneOffset.UTC);

    @TempDir
    Path tempDir;

    private HikariDataSource dataSource;

    @AfterEach
    void close() {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    @Test
    void tracks_incomplete_ingest_run_until_completion() {
        var ledger = ledger();

        var run = ledger.startIngest("source-1", List.of("masks", "hashes"));
        ledger.markDbCommitted(run.runId());

        assertThat(ledger.findIncompleteIngestRuns())
                .singleElement()
                .satisfies(saved -> {
                    assertThat(saved.runId()).isEqualTo(run.runId());
                    assertThat(saved.sourceKey()).isEqualTo("source-1");
                    assertThat(saved.status()).isEqualTo(IngestRunStatus.DB_COMMITTED);
                    assertThat(saved.artifacts()).containsExactly("masks", "hashes");
                });

        ledger.markProjectionCompleted(run.runId());
        ledger.markCompleted(run.runId());

        // Replayed earlier checkpoints observe a later compatible state and
        // must not overwrite the terminal row.
        ledger.markDbCommitted(run.runId());
        ledger.markProjectionCompleted(run.runId());
        ledger.markCompleted(run.runId());

        assertThat(ledger.findIncompleteIngestRuns()).isEmpty();
    }

    @Test
    void cas_rejects_transition_from_incompatible_terminal_state() {
        var ledger = ledger();
        var run = ledger.startIngest("source-failed", List.of("masks"));
        ledger.markFailed(run.runId(), "write failed");

        assertThatThrownBy(() -> ledger.markDbCommitted(run.runId()))
                .isInstanceOf(IocExtractorException.class)
                .hasMessageContaining("transition conflict");
        assertThat(ledger.findIncompleteIngestRuns()).isEmpty();
    }

    @Test
    void upgradesUnlinkedHistoryAndPinsExactlyOneAcceptedRunIncludingAnEmptyPlan() {
        dataSource = dataSource("service-v17.db");
        var migrations = ServiceSchemaMigrations.sqlite();
        new SqliteUserVersionSchemaMigrator(dataSource, migrations.subList(0, 17)).migrate();
        var ledger = new JdbcRunLedger(dataSource, CLOCK);
        var legacy = ledger.startIngest("same-source", List.of("masks"));
        var upgrade = new SqliteUserVersionSchemaMigrator(dataSource, migrations).migrate();
        assertThat(upgrade.appliedVersions()).containsExactly(18);
        var id = new ObservationId("new-observation");
        var registration = new RegisteredObservation(id, "dataframe", new ObservationOrder(41),
                ObservationOrigin.DOCUMENT);
        var identity = new DocumentRun.Identity(registration, new SourceKey("same-source"), "policy", false);
        var run = ledger.beginDocument(identity);
        assertThat(ledger.beginDocument(identity)).isEqualTo(run);
        assertThat(run.plan()).isEmpty();
        assertThatThrownBy(() -> ledger.markDbCommitted(run.run().runId()))
                .isInstanceOf(IocExtractorException.class).hasMessageContaining("transition conflict");
        assertThat(ledger.findIncompleteIngestRuns()).containsExactly(legacy);
        assertThatThrownBy(() -> ledger.beginDocument(
                new DocumentRun.Identity(registration, identity.sourceKey(), "changed-policy", false)))
                .isInstanceOf(IocExtractorException.class).hasMessageContaining("identity");

        var plan = new DocumentRun.Plan(DocumentRun.Plan.Kind.WORKSPACE, "sealed-empty-plan", List.of());
        var pinned = ledger.pinPlan(run, plan);
        assertThat(pinned.plan()).contains(plan);
        assertThat(ledger.pinPlan(run, plan)).isEqualTo(pinned);
        assertThatThrownBy(() -> ledger.pinPlan(run,
                new DocumentRun.Plan(DocumentRun.Plan.Kind.WORKSPACE, "other-seal", List.of("masks"))))
                .isInstanceOf(IocExtractorException.class).hasMessageContaining("plan");
        assertThatThrownBy(() -> ledger.markFailed(run.run().runId(), "rejection lost the plan race"))
                .isInstanceOf(IocExtractorException.class).hasMessageContaining("transition conflict");
        ledger.markDbCommitted(run.run().runId());
        ledger.markProjectionCompleted(run.run().runId());
        ledger.markCompleted(run.run().runId());

        assertThat(ledger.findDocument(id)).hasValueSatisfying(saved -> {
            assertThat(saved.run().runId()).isEqualTo(run.run().runId());
            assertThat(saved.run().status()).isEqualTo(IngestRunStatus.COMPLETED);
            assertThat(saved.identity()).isEqualTo(identity);
            assertThat(saved.plan()).contains(plan);
        });
        assertThat(ledger.findIncompleteIngestRuns()).containsExactly(legacy);
    }

    private JdbcRunLedger ledger() {
        dataSource = dataSource("run-ledger.db");
        new SqliteUserVersionSchemaMigrator(dataSource, ServiceSchemaMigrations.sqlite()).migrate();
        return new JdbcRunLedger(dataSource, CLOCK);
    }

    private HikariDataSource dataSource(String fileName) {
        Path db = tempDir.resolve(fileName);
        return new SqliteDataSourceFactory(new SqlitePragmaPolicy()).create(
                new SqliteDataSourceSettings("service", "jdbc:sqlite:" + db, "low-memory", 1, 1));
    }
}
