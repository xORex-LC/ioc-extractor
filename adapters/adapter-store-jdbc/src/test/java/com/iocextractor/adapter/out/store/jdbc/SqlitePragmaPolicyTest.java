package com.iocextractor.adapter.out.store.jdbc;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SqlitePragmaPolicyTest {

    private final SqlitePragmaPolicy policy = new SqlitePragmaPolicy();

    @Test
    void low_memory_preset_keeps_correctness_invariants_and_small_cache() {
        SqlitePragmaSettings settings = policy.effective("low-memory");

        assertThat(settings.journalMode()).isEqualTo("WAL");
        assertThat(settings.encoding()).isEqualTo("UTF-8");
        assertThat(settings.autoVacuum()).isEqualTo("INCREMENTAL");
        assertThat(settings.foreignKeys()).isTrue();
        assertThat(settings.synchronous()).isEqualTo(SqliteSynchronousMode.NORMAL);
        assertThat(settings.busyTimeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(settings.cacheSize()).isEqualTo(-2_000);
        assertThat(settings.mmapSize()).isZero();
        assertThat(settings.tempStore()).isEqualTo(SqliteTempStore.DEFAULT);
        assertThat(settings.walAutocheckpoint()).isEqualTo(1_000);
        assertThat(settings.journalSizeLimit()).isEqualTo(8_388_608L);
    }

    @Test
    void clamps_busy_timeout_and_synchronous_below_correctness_floor() {
        SqlitePragmaSettings settings = policy.effective(
                "balanced", Duration.ofMillis(10), SqliteSynchronousMode.OFF);

        assertThat(settings.busyTimeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(settings.synchronous()).isEqualTo(SqliteSynchronousMode.NORMAL);
        assertThat(settings.cacheSize()).isEqualTo(-16_000);
        assertThat(settings.mmapSize()).isEqualTo(134_217_728L);
    }

    @Test
    void rejects_unknown_preset() {
        assertThatThrownBy(() -> policy.effective("unsafe-fast"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unsafe-fast");
    }
}
