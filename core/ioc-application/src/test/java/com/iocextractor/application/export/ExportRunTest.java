package com.iocextractor.application.export;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExportRunTest {

    private static final Instant NOW = Instant.parse("2026-06-28T00:00:00Z");
    private static final String HASH = "a".repeat(64);

    @Test
    void status_graph_accepts_only_forward_saga_transitions() {
        assertThat(ExportRunStatus.STARTED.canTransitionTo(ExportRunStatus.STAGED)).isTrue();
        assertThat(ExportRunStatus.STARTED.canTransitionTo(ExportRunStatus.SKIPPED)).isTrue();
        assertThat(ExportRunStatus.STAGED.canTransitionTo(ExportRunStatus.AVAILABLE)).isTrue();
        assertThat(ExportRunStatus.AVAILABLE.canTransitionTo(ExportRunStatus.COMPLETED)).isTrue();

        assertThat(ExportRunStatus.STARTED.canTransitionTo(ExportRunStatus.COMPLETED)).isFalse();
        assertThat(ExportRunStatus.COMPLETED.terminal()).isTrue();
        assertThat(ExportRunStatus.SKIPPED.terminal()).isTrue();
        assertThat(ExportRunStatus.FAILED.terminal()).isTrue();
    }

    @Test
    void staged_and_later_states_require_manifest_hash() {
        assertThatThrownBy(() -> new ExportRun(
                "run-1", "default", ExportRunStatus.STAGED, "ts__run-1", HASH,
                null, NOW, NOW, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("manifestSha256");
    }

    @Test
    void failed_state_requires_reason() {
        assertThatThrownBy(() -> new ExportRun(
                "run-1", "default", ExportRunStatus.FAILED, "ts__run-1", HASH,
                null, NOW, NOW, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requires a reason");
    }

    @Test
    void started_factory_creates_only_initial_state() {
        ExportRun run = ExportRun.started("run-1", "default", "ts__run-1", HASH, NOW);

        assertThat(run.status()).isEqualTo(ExportRunStatus.STARTED);
        assertThat(run.manifestSha256()).isNull();
        assertThat(run.startedAt()).isEqualTo(run.updatedAt());
    }

    @Test
    void run_identity_and_slice_name_must_be_transport_safe() {
        assertThatThrownBy(() -> ExportRun.started(" ", "default", "slice-1", HASH, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("runId");
        assertThatThrownBy(() -> ExportRun.started("run-1", " ", "slice-1", HASH, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("profile");
        for (String sliceName : java.util.List.of("nested/slice", "nested\\slice", ".", "..")) {
            assertThatThrownBy(() -> ExportRun.started(
                    "run-1", "default", sliceName, HASH, NOW))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("single relative path segment");
        }
    }

    @Test
    void run_timestamps_and_hashes_must_preserve_ledger_ordering() {
        assertThatThrownBy(() -> ExportRun.started(
                "run-1", "default", "slice-1", "A".repeat(64), NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("planHash");
        assertThatThrownBy(() -> new ExportRun(
                "run-1", "default", ExportRunStatus.STARTED, "slice-1", HASH,
                null, NOW, NOW.minusSeconds(1), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not precede");
        assertThatThrownBy(() -> new ExportRun(
                "run-1", "default", ExportRunStatus.STARTED, "slice-1", HASH,
                "invalid", NOW, NOW, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("manifestSha256");
    }

    @Test
    void completed_and_failed_runs_require_state_specific_evidence() {
        ExportRun completed = new ExportRun(
                "run-1", "default", ExportRunStatus.COMPLETED, "slice-1", HASH,
                HASH, NOW, NOW.plusSeconds(1), null);
        ExportRun failed = new ExportRun(
                "run-2", "default", ExportRunStatus.FAILED, "slice-2", HASH,
                null, NOW, NOW, "write failed");

        assertThat(completed.manifestSha256()).isEqualTo(HASH);
        assertThat(failed.reason()).isEqualTo("write failed");
        assertThatThrownBy(() -> new ExportRun(
                "run-3", "default", ExportRunStatus.FAILED, "slice-3", HASH,
                null, NOW, NOW, " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requires a reason");
    }
}
