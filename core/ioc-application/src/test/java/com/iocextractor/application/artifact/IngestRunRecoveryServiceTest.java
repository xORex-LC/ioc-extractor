package com.iocextractor.application.artifact;

import com.iocextractor.application.port.out.artifact.ArtifactProjection;
import com.iocextractor.application.port.out.artifact.ArtifactProjectionCommand;
import com.iocextractor.application.port.out.artifact.ArtifactProjectionResult;
import com.iocextractor.application.port.out.artifact.RunLedger;
import com.iocextractor.diagnostics.DiagnosticFactory;
import com.iocextractor.diagnostics.DiagnosticSeverity;
import com.iocextractor.diagnostics.codes.IngestDiagnosticCodes;
import com.iocextractor.diagnostics.sink.CollectingDiagnosticSink;
import com.iocextractor.diagnostics.sink.NoopDiagnosticSink;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

class IngestRunRecoveryServiceTest {

    private static final DiagnosticFactory DIAGNOSTICS = new DiagnosticFactory(
            java.time.Clock.fixed(Instant.EPOCH, java.time.ZoneOffset.UTC));

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
        var service = new IngestRunRecoveryService(ledger, projection, NoopDiagnosticSink.INSTANCE);

        assertThat(service.recover()).isEqualTo(1);

        assertThat(projection.requests)
                .extracting(ArtifactProjectionCommand::runId)
                .containsOnly("run-1");
        assertThat(projection.requests)
                .extracting(ArtifactProjectionCommand::artifactName)
                .containsExactly("masks", "hashes");
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
        var service = new IngestRunRecoveryService(ledger, projection, NoopDiagnosticSink.INSTANCE);

        service.recover();

        assertThat(projection.requests).isEmpty();
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
        var service = new IngestRunRecoveryService(ledger, projection, NoopDiagnosticSink.INSTANCE);

        service.recover();

        assertThat(projection.requests).isEmpty();
        assertThat(ledger.status("run-3")).isEqualTo(IngestRunStatus.COMPLETED);
        assertThat(Snapshot.capture(projection.rows)).isEqualTo(before);
    }

    @Test
    void emits_projection_diagnostics_with_the_durable_run_identity() {
        var ledger = new MemoryRunLedger(new IngestRun(
                "run-4", "source-4", IngestRunStatus.DB_COMMITTED, List.of("masks"),
                Instant.EPOCH, Instant.EPOCH, null));
        var warning = DIAGNOSTICS.create(IngestDiagnosticCodes.SOURCE_UNREADABLE)
                .severity(DiagnosticSeverity.WARN)
                .with("source", "run-4")
                .with("reason", "lossy projection")
                .build();
        var projection = new CollectingProjection(new ArtifactProjectionResult(2, List.of(warning)));
        var sink = new CollectingDiagnosticSink();

        new IngestRunRecoveryService(ledger, projection, sink).recover();

        assertThat(projection.requests).singleElement().satisfies(request -> {
            assertThat(request.runId()).isEqualTo("run-4");
            assertThat(request.artifactName()).isEqualTo("masks");
        });
        assertThat(sink.diagnostics()).containsExactly(warning);
        assertThat(ledger.status("run-4")).isEqualTo(IngestRunStatus.COMPLETED);
    }

    private static final class CollectingProjection implements ArtifactProjection {
        private final List<ArtifactProjectionCommand> requests = new ArrayList<>();
        private final Map<String, List<String>> rows = Map.of(
                "masks", List.of("1:example.com", "2:example.org"),
                "hashes", List.of("10:ABCD"));
        private final ArtifactProjectionResult outcome;

        private CollectingProjection() {
            this(null);
        }

        private CollectingProjection(ArtifactProjectionResult outcome) {
            this.outcome = outcome;
        }

        @Override
        public ArtifactProjectionResult project(ArtifactProjectionCommand request) {
            requests.add(request);
            return outcome == null
                    ? ArtifactProjectionResult.clean(rows.getOrDefault(request.artifactName(), List.of()).size())
                    : outcome;
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
