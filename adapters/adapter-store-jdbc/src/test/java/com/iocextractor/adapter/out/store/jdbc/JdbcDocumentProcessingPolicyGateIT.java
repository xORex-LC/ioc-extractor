package com.iocextractor.adapter.out.store.jdbc;

import com.iocextractor.application.tck.junit.IntegrationTest;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Path;
import java.sql.Connection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Policy changes require an empty durable document lane; unchanged restarts can recover. */
@IntegrationTest
@Timeout(20)
class JdbcDocumentProcessingPolicyGateIT {
    @TempDir Path tempDir;

    @Test
    void blocks_activation_and_policy_change_until_external_and_journaled_work_drains() throws Exception {
        try (HikariDataSource dataSource = new SqliteDataSourceFactory(new SqlitePragmaPolicy()).create(
                new SqliteDataSourceSettings("service", "jdbc:sqlite:" + tempDir.resolve("policy.db"),
                        "low-memory", 1, 1))) {
            new SqliteUserVersionSchemaMigrator(dataSource, ServiceSchemaMigrations.sqlite()).migrate();
            var gate = new JdbcDocumentProcessingPolicyGate(dataSource);
            String old = "a".repeat(64);
            String changed = "b".repeat(64);

            assertThatThrownBy(() -> gate.ensure(null, true, () -> true))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("SHA-256");
            assertThatThrownBy(() -> gate.ensure("not-a-fingerprint", true, () -> true))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("SHA-256");
            assertThat(policy(dataSource)).isNull();

            gate.ensure(old, false, () -> false);
            assertThat(policy(dataSource)).isNull();
            assertThatThrownBy(() -> gate.ensure(old, true, () -> false))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("unfinished intake");
            gate.ensure(old, true, () -> true);
            gate.ensure(old, true, () -> false);
            assertThat(policy(dataSource)).isEqualTo(old);

            assertThatThrownBy(() -> gate.ensure(changed, true, () -> false))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("unfinished intake");
            reserveDocument(dataSource);
            assertThatThrownBy(() -> gate.ensure(changed, true, () -> true))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("unfinished intake");
            assertThat(policy(dataSource)).isEqualTo(old);
            try (Connection connection = dataSource.getConnection()) {
                connection.createStatement().executeUpdate("""
                        UPDATE document_admission SET phase = 'TERMINAL', admission_order = 1,
                            dataframe_namespace = 'primary', claimed_size = 1,
                            source_key = 'pending', terminal_outcome = 'COMPLETED'
                        WHERE occurrence_id = 'pending'
                        """);
            }
            assertThatThrownBy(() -> gate.ensure(changed, true, () -> true))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("unfinished intake");
            try (Connection connection = dataSource.getConnection()) {
                connection.createStatement().executeUpdate("""
                        UPDATE document_admission SET registration_finalized = 1
                        WHERE occurrence_id = 'pending'
                        """);
            }
            gate.ensure(changed, true, () -> true);
            assertThat(policy(dataSource)).isEqualTo(changed);
        }
    }

    private static String policy(HikariDataSource dataSource) throws Exception {
        try (Connection connection = dataSource.getConnection();
             var query = connection.createStatement().executeQuery(
                     "SELECT policy_fingerprint FROM document_processing_policy WHERE id = 1")) {
            return query.next() ? query.getString(1) : null;
        }
    }

    private static void reserveDocument(HikariDataSource dataSource) throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            connection.createStatement().executeUpdate("""
                    INSERT INTO document_admission (
                        occurrence_id, candidate_path, candidate_size, candidate_mtime_ns,
                        claim_path, phase, created_at_ms, updated_at_ms)
                    VALUES ('pending', 'inbox/pending.docx', 1, 1,
                            'processing/pending.docx', 'RESERVED', 1, 1)
                    """);
        }
    }
}
