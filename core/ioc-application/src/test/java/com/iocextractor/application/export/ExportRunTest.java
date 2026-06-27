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
}
