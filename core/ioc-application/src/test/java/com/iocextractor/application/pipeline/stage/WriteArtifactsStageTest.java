package com.iocextractor.application.pipeline.stage;

import com.iocextractor.application.artifact.ArtifactIdSequence;
import com.iocextractor.application.artifact.ArtifactIdStrategy;
import com.iocextractor.application.artifact.ArtifactRow;
import com.iocextractor.application.artifact.ArtifactWritePlan;
import com.iocextractor.application.artifact.CanonicalArtifact;
import com.iocextractor.application.artifact.CanonicalWriteResult;
import com.iocextractor.application.artifact.PreparedArtifactRow;
import com.iocextractor.application.pipeline.payload.PreparedArtifacts;
import com.iocextractor.application.port.out.artifact.CanonicalArtifactRepository;
import com.iocextractor.application.port.out.artifact.ArtifactProjectionResult;
import com.iocextractor.diagnostics.DiagnosticException;
import com.iocextractor.diagnostics.DiagnosticFactory;
import com.iocextractor.diagnostics.DiagnosticSeverity;
import com.iocextractor.diagnostics.codes.IngestDiagnosticCodes;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WriteArtifactsStageTest {

    @Test
    void commits_plans_in_order_and_projects_after_each_write() {
        var repository = new RecordingRepository();
        var projected = new ArrayList<String>();
        var stage = new WriteArtifactsStage(repository, request -> {
            projected.add(request.artifactName());
            return ArtifactProjectionResult.clean(1);
        },
                new DiagnosticFactory(StageTestSupport.CLOCK));
        var prepared = new PreparedArtifacts(2, 1, List.of(plan("masks"), plan("hashes")));

        var output = stage.process(StageTestSupport.envelope(prepared, false));

        assertThat(output.payload().writtenPerArtifact().keySet()).containsExactly("masks", "hashes");
        assertThat(output.payload().writtenPerArtifact()).containsEntry("masks", 1).containsEntry("hashes", 1);
        assertThat(repository.names).containsExactly("masks", "hashes");
        assertThat(projected).containsExactly("masks", "hashes");
        assertThat(repository.artifacts.getFirst().rows().getFirst().value("id")).isEqualTo("10");
    }

    @Test
    void dry_run_does_not_reserve_ids_write_or_project() {
        var repository = new RecordingRepository();
        var projected = new ArrayList<String>();
        var ids = new ArtifactIdSequence(ArtifactIdStrategy.ASCENDING, 10);
        var stage = new WriteArtifactsStage(repository, request -> {
            projected.add(request.artifactName());
            return ArtifactProjectionResult.clean(1);
        },
                new DiagnosticFactory(StageTestSupport.CLOCK));
        var prepared = new PreparedArtifacts(1, 1, List.of(plan("masks", ids)));

        var output = stage.process(StageTestSupport.envelope(prepared, true));

        assertThat(output.payload().writtenPerArtifact()).isEmpty();
        assertThat(repository.artifacts).isEmpty();
        assertThat(projected).isEmpty();
        assertThat(ids.reserve(1).start()).isEqualTo(10);
    }

    @Test
    void attaches_projection_diagnostics_to_the_stage_envelope() {
        var warning = StageTestSupport.DIAGNOSTICS.create(IngestDiagnosticCodes.SOURCE_UNREADABLE)
                .severity(DiagnosticSeverity.WARN)
                .with("source", "projection")
                .with("reason", "lossy value")
                .build();
        var requests = new ArrayList<String>();
        var stage = new WriteArtifactsStage(new RecordingRepository(), request -> {
            requests.add(request.runId() + ":" + request.artifactName());
            return new ArtifactProjectionResult(1, List.of(warning));
        }, StageTestSupport.DIAGNOSTICS);

        var output = stage.process(StageTestSupport.envelope(
                new PreparedArtifacts(1, 1, List.of(plan("masks"))), false));

        assertThat(requests).containsExactly("run-1:masks");
        assertThat(output.diagnostics()).containsExactly(warning);
    }

    @Test
    void identifies_post_commit_projection_failure() {
        var repository = new RecordingRepository();
        var stage = new WriteArtifactsStage(repository,
                ignored -> {
                    throw new IllegalStateException("disk full");
                },
                new DiagnosticFactory(StageTestSupport.CLOCK));
        var prepared = new PreparedArtifacts(1, 1, List.of(plan("masks")));

        assertThatThrownBy(() -> stage.process(StageTestSupport.envelope(prepared, false)))
                .isInstanceOf(DiagnosticException.class)
                .satisfies(failure -> assertThat(((DiagnosticException) failure).diagnostic().context())
                        .containsEntry("sink", "projection")
                        .containsEntry("artifact", "masks"));
        assertThat(repository.names).containsExactly("masks");
    }

    private ArtifactWritePlan plan(String name) {
        return plan(name, new ArtifactIdSequence(ArtifactIdStrategy.ASCENDING, 10));
    }

    private ArtifactWritePlan plan(String name, ArtifactIdSequence ids) {
        return new ArtifactWritePlan(name, List.of("id", "value"), List.of(
                new PreparedArtifactRow(ArtifactRow.ordered(
                        java.util.Map.of("id", "0", "value", name)), Optional.of("id"))), ids);
    }

    private static final class RecordingRepository implements CanonicalArtifactRepository {
        private final List<String> names = new ArrayList<>();
        private final List<CanonicalArtifact> artifacts = new ArrayList<>();

        @Override
        public CanonicalArtifact load(String artifactName) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CanonicalWriteResult write(String artifactName, CanonicalArtifact artifact) {
            names.add(artifactName);
            artifacts.add(artifact);
            return new CanonicalWriteResult(artifact.rows().size(), 1);
        }
    }
}
