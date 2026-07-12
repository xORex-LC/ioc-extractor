package com.iocextractor.application.pipeline.stage;

import com.iocextractor.application.artifact.ArtifactIdSequence;
import com.iocextractor.application.artifact.ArtifactIdStrategy;
import com.iocextractor.application.artifact.ArtifactRow;
import com.iocextractor.application.artifact.ArtifactWritePlan;
import com.iocextractor.application.artifact.CanonicalArtifact;
import com.iocextractor.application.artifact.CanonicalWriteResult;
import com.iocextractor.application.artifact.PreparedArtifactRow;
import com.iocextractor.application.pipeline.payload.RetainedIndicators;
import com.iocextractor.application.port.out.artifact.ArtifactPreparer;
import com.iocextractor.application.port.out.artifact.CanonicalArtifactRepository;
import com.iocextractor.diagnostics.Diagnostic;
import com.iocextractor.diagnostics.DiagnosticException;
import com.iocextractor.diagnostics.DiagnosticFactory;
import com.iocextractor.diagnostics.codes.SinkDiagnosticCodes;
import com.iocextractor.diagnostics.result.FailurePolicy;
import com.iocextractor.diagnostics.result.Result;
import com.iocextractor.diagnostics.sink.CollectingDiagnosticSink;
import com.iocextractor.platform.etl.Envelope;
import com.iocextractor.platform.etl.NoopPipelineObserver;
import com.iocextractor.platform.etl.Pipeline;
import com.iocextractor.platform.etl.PipelineRunner;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ArtifactPolicyCheckpointTest {

    private static final int INPUT_SIZE = 5_000;
    private static final long FIRST_ID = 100;

    @Test
    void fail_fast_rejects_one_invalid_row_before_write_or_id_reservation() {
        var ids = new ArtifactIdSequence(ArtifactIdStrategy.ASCENDING, FIRST_ID);
        var repository = new RecordingRepository(false);
        var runner = runner(FailurePolicy.failFast());

        assertThatThrownBy(() -> runner.run(input(), pipeline(preparer(ids, true), repository)))
                .isInstanceOf(DiagnosticException.class)
                .extracting("diagnostic.code")
                .isEqualTo(SinkDiagnosticCodes.ROW_MAPPING_FAILED);
        assertThat(repository.writtenRows).isZero();
        assertThat(ids.reserve(1).start()).isEqualTo(FIRST_ID);
    }

    @Test
    void collect_commits_all_valid_rows_and_keeps_element_error_in_outcome() {
        var ids = new ArtifactIdSequence(ArtifactIdStrategy.ASCENDING, FIRST_ID);
        var repository = new RecordingRepository(false);

        var output = runner(FailurePolicy.collectAndContinue())
                .run(input(), pipeline(preparer(ids, true), repository));

        assertThat(repository.writtenRows).isEqualTo(INPUT_SIZE - 1);
        assertThat(output.payload().writtenPerArtifact()).containsEntry("masks", INPUT_SIZE - 1);
        assertThat(output.diagnostics()).extracting(diagnostic -> diagnostic.code())
                .containsExactly(SinkDiagnosticCodes.ROW_MAPPING_FAILED);
    }

    @Test
    void storage_failure_is_run_diagnostic_and_reserved_range_is_not_reused() {
        var ids = new ArtifactIdSequence(ArtifactIdStrategy.ASCENDING, FIRST_ID);
        var repository = new RecordingRepository(true);
        var sink = new CollectingDiagnosticSink();
        var runner = new PipelineRunner(FailurePolicy.collectAndContinue(),
                new NoopPipelineObserver(), sink, new DiagnosticFactory(StageTestSupport.CLOCK));

        assertThatThrownBy(() -> runner.run(input(), pipeline(preparer(ids, false), repository)))
                .isInstanceOf(DiagnosticException.class)
                .extracting("diagnostic.code")
                .isEqualTo(SinkDiagnosticCodes.WRITE_FAILED);
        assertThat(sink.diagnostics()).singleElement()
                .satisfies(diagnostic -> assertThat(diagnostic.code()).isEqualTo(SinkDiagnosticCodes.WRITE_FAILED));
        assertThat(ids.reserve(1).start()).isEqualTo(FIRST_ID + INPUT_SIZE);
    }

    private Pipeline<RetainedIndicators, com.iocextractor.application.pipeline.payload.ArtifactWriteSummary> pipeline(
            ArtifactPreparer preparer,
            CanonicalArtifactRepository repository) {
        return Pipeline.<RetainedIndicators>start()
                .then(new PrepareArtifactsStage(List.of(preparer)))
                .then(new WriteArtifactsStage(repository, ignored -> { },
                        new DiagnosticFactory(StageTestSupport.CLOCK)));
    }

    private PipelineRunner runner(FailurePolicy policy) {
        return new PipelineRunner(policy, new NoopPipelineObserver(),
                new CollectingDiagnosticSink(), new DiagnosticFactory(StageTestSupport.CLOCK));
    }

    private Envelope<RetainedIndicators> input() {
        var classified = StageTestSupport.classifiedIndicator(StageTestSupport.indicator("example.com"));
        var indicators = Collections.nCopies(INPUT_SIZE, classified);
        return StageTestSupport.envelope(new RetainedIndicators(indicators, indicators), false);
    }

    private ArtifactPreparer preparer(ArtifactIdSequence ids, boolean oneInvalid) {
        return new ArtifactPreparer() {
            @Override
            public String name() {
                return "masks";
            }

            @Override
            public Result<ArtifactWritePlan> prepare(
                    List<com.iocextractor.application.pipeline.payload.ClassifiedIndicator> indicators) {
                int valid = indicators.size() - (oneInvalid ? 1 : 0);
                var rows = new ArrayList<PreparedArtifactRow>(valid);
                for (int index = 0; index < valid; index++) {
                    rows.add(new PreparedArtifactRow(
                            ArtifactRow.ordered(java.util.Map.of("id", "0", "mask", "example.com-" + index)),
                            Optional.of("id")));
                }
                var plan = new ArtifactWritePlan("masks", List.of("id", "mask"), rows, ids);
                if (!oneInvalid) {
                    return Result.success(plan);
                }
                Diagnostic diagnostic = Diagnostic.builder(SinkDiagnosticCodes.ROW_MAPPING_FAILED,
                                StageTestSupport.CLOCK)
                        .with("sink", "masks")
                        .with("indicator", "#4999:DOMAIN")
                        .with("reason", "unmappable")
                        .build();
                return Result.of(plan, List.of(diagnostic));
            }
        };
    }

    private static final class RecordingRepository implements CanonicalArtifactRepository {
        private final boolean fail;
        private int writtenRows;

        private RecordingRepository(boolean fail) {
            this.fail = fail;
        }

        @Override
        public CanonicalArtifact load(String artifactName) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CanonicalWriteResult write(String artifactName, CanonicalArtifact artifact) {
            if (fail) {
                throw new IllegalStateException("database unavailable");
            }
            writtenRows += artifact.rows().size();
            return new CanonicalWriteResult(artifact.rows().size(), artifact.rows().isEmpty() ? 0 : 1);
        }
    }
}
