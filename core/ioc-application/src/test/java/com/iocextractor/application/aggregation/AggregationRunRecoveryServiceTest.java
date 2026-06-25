package com.iocextractor.application.aggregation;

import com.iocextractor.application.port.out.aggregation.ArtifactProjection;
import com.iocextractor.application.port.out.aggregation.RunLedger;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

class IngestRunRecoveryServiceTest {

    @Test
    void reprojects_db_committed_runs_and_marks_them_completed() {
        var ledger = new MemoryRunLedger(new IngestRun(
                "run-1",
                "source-1",
                IngestRunStatus.DB_COMMITTED,
                List.of("masks", "hashes"),
                Instant.EPOCH,
                Instant.EPOCH,
                null));
        var projection = new CollectingProjection();
        Snapshot before = Snapshot.capture(projection.rows);
        var service = new IngestRunRecoveryService(ledger, projection);

        assertThat(service.recover()).isEqualTo(1);

        assertThat(projection.artifacts).containsExactly("masks", "hashes");
        assertThat(ledger.status("run-1")).isEqualTo(IngestRunStatus.COMPLETED);
        assertThat(Snapshot.capture(projection.rows)).isEqualTo(before);
    }

    @Test
    void marks_started_runs_failed_without_projecting() {
        var ledger = new MemoryRunLedger(new IngestRun(
                "run-2",
                "source-2",
                IngestRunStatus.STARTED,
                List.of("masks"),
                Instant.EPOCH,
                Instant.EPOCH,
                null));
        var projection = new CollectingProjection();
        Snapshot before = Snapshot.capture(projection.rows);
        var service = new IngestRunRecoveryService(ledger, projection);

        service.recover();

        assertThat(projection.artifacts).isEmpty();
        assertThat(ledger.status("run-2")).isEqualTo(IngestRunStatus.FAILED);
        assertThat(Snapshot.capture(projection.rows)).isEqualTo(before);
    }

    @Test
    void closes_projection_completed_runs_without_reprojecting() {
        var ledger = new MemoryRunLedger(new IngestRun(
                "run-3",
                "source-3",
                IngestRunStatus.PROJECTION_COMPLETED,
                List.of("masks"),
                Instant.EPOCH,
                Instant.EPOCH,
                null));
        var projection = new CollectingProjection();
        Snapshot before = Snapshot.capture(projection.rows);
        var service = new IngestRunRecoveryService(ledger, projection);

        service.recover();

        assertThat(projection.artifacts).isEmpty();
        assertThat(ledger.status("run-3")).isEqualTo(IngestRunStatus.COMPLETED);
        assertThat(Snapshot.capture(projection.rows)).isEqualTo(before);
    }

    private static final class CollectingProjection implements ArtifactProjection {
        private final List<String> artifacts = new ArrayList<>();
        private final Map<String, List<String>> rows = Map.of(
                "masks", List.of("1:example.com", "2:example.org"),
                "hashes", List.of("10:ABCD"));

        @Override
        public void project(String artifactName) {
            artifacts.add(artifactName);
        }
    }

    private record Snapshot(int count, int checksum) {

        private static Snapshot capture(Map<String, List<String>> rows) {
            List<String> values = rows.values().stream()
                    .flatMap(List::stream)
                    .sorted()
                    .toList();
            return new Snapshot(values.size(), Objects.hash(values));
        }
    }

    private static final class MemoryRunLedger implements RunLedger {
        private final List<IngestRun> runs = new ArrayList<>();

        private MemoryRunLedger(IngestRun... runs) {
            this.runs.addAll(List.of(runs));
        }

        @Override
        public IngestRun startIngest(String sourceKey, List<String> artifacts) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void markDbCommitted(String runId) {
            replace(runId, IngestRunStatus.DB_COMMITTED, null);
        }

        @Override
        public void markProjectionCompleted(String runId) {
            replace(runId, IngestRunStatus.PROJECTION_COMPLETED, null);
        }

        @Override
        public void markCompleted(String runId) {
            replace(runId, IngestRunStatus.COMPLETED, null);
        }

        @Override
        public void markFailed(String runId, String reason) {
            replace(runId, IngestRunStatus.FAILED, reason);
        }

        @Override
        public List<IngestRun> findIncompleteIngestRuns() {
            return runs.stream()
                    .filter(run -> run.status() == IngestRunStatus.STARTED
                            || run.status() == IngestRunStatus.DB_COMMITTED
                            || run.status() == IngestRunStatus.PROJECTION_COMPLETED)
                    .toList();
        }

        private IngestRunStatus status(String runId) {
            return runs.stream()
                    .filter(run -> run.runId().equals(runId))
                    .findFirst()
                    .orElseThrow()
                    .status();
        }

        private void replace(String runId, IngestRunStatus status, String reason) {
            for (int i = 0; i < runs.size(); i++) {
                IngestRun run = runs.get(i);
                if (run.runId().equals(runId)) {
                    runs.set(i, new IngestRun(run.runId(), run.sourceKey(), status, run.artifacts(),
                            run.startedAt(), Instant.EPOCH, reason));
                    return;
                }
            }
        }
    }
}
