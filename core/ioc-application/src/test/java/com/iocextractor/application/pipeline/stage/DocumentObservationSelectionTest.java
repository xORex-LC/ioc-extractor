package com.iocextractor.application.pipeline.stage;

import com.iocextractor.application.artifact.*;
import com.iocextractor.application.artifact.policy.ArtifactWritePolicy;
import com.iocextractor.application.pipeline.payload.IndicatorOccurrence;
import com.iocextractor.application.port.out.artifact.ArtifactPreparer;
import com.iocextractor.application.port.out.artifact.DocumentProcessingPlan;
import com.iocextractor.diagnostics.codes.PipelineDiagnosticCodes;
import com.iocextractor.diagnostics.result.Result;
import com.iocextractor.domain.model.Indicator;
import com.iocextractor.domain.model.IndicatorType;
import com.iocextractor.domain.model.SourceContext;
import com.iocextractor.processing.model.ClassifiedIndicator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class DocumentObservationSelectionTest {
    @Test
    void sourceSelectionKeepsFirstProvenanceAndReportsDroppedObservation() {
        var output = stage(true, true, false).process(StageTestSupport.envelope(
                StageTestSupport.attributedIndicators(indicator("one", "first"), indicator("one", "later")), false));

        assertThat(output.payload().extracted()).isEqualTo(2);
        assertThat(output.payload().retained()).isOne();
        assertThat(output.payload().plans().getFirst().rows()).singleElement()
                .satisfies(row -> assertThat(row.template().value("source")).isEqualTo("first"));
        assertThat(output.diagnostics()).singleElement().satisfies(diagnostic -> {
            assertThat(diagnostic.code()).isEqualTo(PipelineDiagnosticCodes.ITEM_SKIPPED);
            assertThat(diagnostic.context()).containsEntry("item", "one");
        });
    }

    @Test
    void disabledSourceDedupPreservesBothRowsIdsAndProvenance() {
        var output = stage(false, true, false).process(StageTestSupport.envelope(
                StageTestSupport.attributedIndicators(indicator("one", "first"), indicator("one", "later")), false));

        assertThat(output.payload().retained()).isEqualTo(2);
        assertThat(output.diagnostics()).isEmpty();
        assertThat(output.payload().plans().getFirst().materialize().rows())
                .extracting(row -> row.value("id"), row -> row.value("source"))
                .containsExactly(org.assertj.core.groups.Tuple.tuple("100", "first"),
                        org.assertj.core.groups.Tuple.tuple("101", "later"));
    }

    @Test
    void sourceSelectionReservesEveryMappedCollisionButFinalKeySelectionCoalescesIt() {
        var source = StageTestSupport.attributedIndicators(indicator("one", "first"), indicator("two", "later"));
        assertThat(stage(true, true, false).process(StageTestSupport.envelope(source, false)).payload()
                .plans().getFirst().materialize().rows()).extracting(row -> row.value("id"))
                .containsExactly("100", "101");
        assertThat(stage(true, false, false).process(StageTestSupport.envelope(source, false)).payload()
                .plans().getFirst().materialize().rows()).extracting(row -> row.value("id"))
                .containsExactly("100");
    }

    @Test
    void sourceSelectionStillPassesAllOccurrencesToLastNonemptyPolicy() {
        var output = stage(true, true, true).process(StageTestSupport.envelope(
                StageTestSupport.attributedIndicators(indicator("one", "first"), indicator("one", "later")), false));
        assertThat(output.payload().retained()).isOne();
        assertThat(output.payload().plans().getFirst().rows()).singleElement()
                .satisfies(row -> assertThat(row.template().value("source")).isEqualTo("later"));
    }

    private PrepareRoutedArtifactsStage stage(boolean deduplicate, boolean sourceSelection, boolean last) {
        var ids = new ArtifactIdSequence(ArtifactIdStrategy.ASCENDING, 100);
        ArtifactPreparer preparer = new ArtifactPreparer() {
            @Override public String name() { return "test"; }
            @Override public Result<ArtifactWritePlan> prepare(List<ClassifiedIndicator> values) {
                return Result.success(new ArtifactWritePlan("test", List.of("id", "value", "source"), List.of(), ids));
            }
        };
        DocumentProcessingPlan plan = new DocumentProcessingPlan() {
            @Override public DocumentObservationSelection observationSelection() {
                return sourceSelection ? DocumentObservationSelection.RETAINED_OBSERVATIONS : DocumentObservationSelection.FINAL_KEY;
            }
            @Override public Result<List<RoutedArtifactCandidate>> prepare(IndicatorOccurrence occurrence) {
                return Result.success(List.of(new RoutedArtifactCandidate("test", new PreparedArtifactRow(
                        ArtifactRow.ordered(Map.of("id", "0", "value", "same-key", "source",
                                occurrence.indicator().source().label())), Optional.of("id")))));
            }
        };
        var policy = new ArtifactWritePolicy(last ? ArtifactWritePolicy.DuplicateSelection.LAST_NONEMPTY
                : ArtifactWritePolicy.DuplicateSelection.KEEP_FIRST, last ? "source" : null, Map.of());
        return new PrepareRoutedArtifactsStage(plan, List.of(preparer),
                (artifact, row) -> Optional.of(new ArtifactRowKey(row.value("value"))), Map.of("test", policy), deduplicate);
    }

    private Indicator indicator(String value, String source) {
        return new Indicator(value, IndicatorType.DOMAIN, new SourceContext(source, null));
    }
}
