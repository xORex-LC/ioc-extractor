package com.iocextractor.adapter.processing.camel;

import com.iocextractor.adapter.processing.camel.RouterQualification.Mode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.assertj.core.api.Assertions.*;

/** R5's 1,000-input correctness smoke across branch, caller and outcome dimensions. */
@Timeout(120)
class RouterQualificationTest {
    @Test void syntheticWorkloadPreservesOutcomesAcrossFanoutAndConcurrentCallers() throws Exception {
        for (int branches : new int[] { 1, 4, 16, 64 }) {
            for (int callers : new int[] { 1, 4 }) {
                for (Mode mode : Mode.values()) {
                    var result = RouterQualification.measure(1_000, branches, callers, mode);
                    assertThat(result.prepared()).isEqualTo(
                            (mode == Mode.FAILURE ? 750L : 1_000L) * branches);
                    assertThat(result.blocked()).isEqualTo(mode == Mode.FAILURE ? 250 : 0);
                    assertThat(result.recovered()).isEqualTo(mode == Mode.RECOVERY ? 250 : 0);
                    assertThat(result.elapsedNanos()).isPositive();
                    assertThat(result.startupNanos()).isPositive();
                }
            }
        }
    }
}
