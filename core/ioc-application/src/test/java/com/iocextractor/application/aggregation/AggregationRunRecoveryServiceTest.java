package com.iocextractor.application.aggregation;

import com.iocextractor.application.port.out.aggregation.ArtifactProjection;
import com.iocextractor.application.port.out.aggregation.RunLedger;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AggregationRunRecoveryServiceTest {

    @Test
    void reprojects_db_committed_runs_and_marks_them_completed() {
        var ledger = new MemoryRunLedger(new AggregationRun(
                "run-1",
                AggregationRunStatus.DB_COMMITTED,
                List.of("masks", "hashes"),
                Instant.EPOCH,
                Instant.EPOCH,
                null));
        var projection = new CollectingProjection();
        var service = new AggregationRunRecoveryService(ledger, projection);

        assertThat(service.recover()).isEqualTo(1);

        assertThat(projection.artifacts).containsExactly("masks", "hashes");
        assertThat(ledger.status("run-1")).isEqualTo(AggregationRunStatus.COMPLETED);
    }

    @Test
    void marks_started_runs_failed_without_projecting() {
        var ledger = new MemoryRunLedger(new AggregationRun(
                "run-2",
                AggregationRunStatus.STARTED,
                List.of("masks"),
                Instant.EPOCH,
                Instant.EPOCH,
                null));
        var projection = new CollectingProjection();
        var service = new AggregationRunRecoveryService(ledger, projection);

        service.recover();

        assertThat(projection.artifacts).isEmpty();
        assertThat(ledger.status("run-2")).isEqualTo(AggregationRunStatus.FAILED);
    }

    private static final class CollectingProjection implements ArtifactProjection {
        private final List<String> artifacts = new ArrayList<>();

        @Override
        public void project(String artifactName) {
            artifacts.add(artifactName);
        }
    }

    private static final class MemoryRunLedger implements RunLedger {
        private final List<AggregationRun> runs = new ArrayList<>();

        private MemoryRunLedger(AggregationRun... runs) {
            this.runs.addAll(List.of(runs));
        }

        @Override
        public AggregationRun startAggregation(List<String> artifacts) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void markDbCommitted(String runId) {
            replace(runId, AggregationRunStatus.DB_COMMITTED, null);
        }

        @Override
        public void markProjectionCompleted(String runId) {
            replace(runId, AggregationRunStatus.PROJECTION_COMPLETED, null);
        }

        @Override
        public void markCompleted(String runId) {
            replace(runId, AggregationRunStatus.COMPLETED, null);
        }

        @Override
        public void markFailed(String runId, String reason) {
            replace(runId, AggregationRunStatus.FAILED, reason);
        }

        @Override
        public List<AggregationRun> findIncompleteAggregationRuns() {
            return runs.stream()
                    .filter(run -> run.status() == AggregationRunStatus.STARTED
                            || run.status() == AggregationRunStatus.DB_COMMITTED
                            || run.status() == AggregationRunStatus.PROJECTION_COMPLETED)
                    .toList();
        }

        private AggregationRunStatus status(String runId) {
            return runs.stream()
                    .filter(run -> run.runId().equals(runId))
                    .findFirst()
                    .orElseThrow()
                    .status();
        }

        private void replace(String runId, AggregationRunStatus status, String reason) {
            for (int i = 0; i < runs.size(); i++) {
                AggregationRun run = runs.get(i);
                if (run.runId().equals(runId)) {
                    runs.set(i, new AggregationRun(run.runId(), status, run.artifacts(),
                            run.startedAt(), Instant.EPOCH, reason));
                    return;
                }
            }
        }
    }
}
