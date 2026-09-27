package com.iocextractor.application.pipeline.stage;

import com.iocextractor.application.artifact.ArtifactIdSequence;
import com.iocextractor.application.artifact.ArtifactIdStrategy;
import com.iocextractor.application.artifact.ArtifactRow;
import com.iocextractor.application.artifact.ArtifactRowKey;
import com.iocextractor.application.artifact.ArtifactWritePlan;
import com.iocextractor.application.artifact.PreparedArtifactRow;
import com.iocextractor.application.artifact.RoutedArtifactCandidate;
import com.iocextractor.application.artifact.policy.ArtifactWritePolicy;
import com.iocextractor.application.pipeline.payload.IndicatorOccurrence;
import com.iocextractor.application.port.out.artifact.ArtifactPreparer;
import com.iocextractor.application.port.out.artifact.DocumentProcessingPlan;
import com.iocextractor.diagnostics.result.Result;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PrepareRoutedArtifactsStageTest {
    private static final ArtifactWritePolicy KEEP_FIRST = ArtifactWritePolicy.legacy();

    @Test
    void final_host_key_merges_masks_but_original_urls_remain_distinct_in_blacklist() {
        var source = StageTestSupport.attributedIndicators(
                StageTestSupport.indicator("https://best-malware.com/a"),
                StageTestSupport.indicator("https://best-malware.com/b"));
        DocumentProcessingPlan routing = occurrence -> Result.success(List.of(
                candidate("masks", "mask", "best-malware.com", occurrence),
                candidate("address_blacklist", "forbidden_url", occurrence.indicator().value(), occurrence)));
        var stage = new PrepareRoutedArtifactsStage(routing,
                List.of(empty("masks", "mask"), empty("address_blacklist", "forbidden_url")),
                (artifact, row) -> Optional.of(new ArtifactRowKey(row.value(
                        "masks".equals(artifact) ? "mask" : "forbidden_url"))),
                Map.of("masks", KEEP_FIRST, "address_blacklist", KEEP_FIRST), true);

        var output = stage.process(StageTestSupport.envelope(source, false)).payload();

        assertThat(output.extracted()).isEqualTo(2);
        assertThat(output.retained()).isEqualTo(2);
        assertThat(output.plans().get(0).rows()).hasSize(1);
        assertThat(output.plans().get(0).rows().getFirst().template().value("mask"))
                .isEqualTo("best-malware.com");
        assertThat(output.plans().get(1).rows()).extracting(row -> row.template().value("forbidden_url"))
                .containsExactly("https://best-malware.com/a", "https://best-malware.com/b");
    }

    @Test
    void last_nonempty_selection_uses_the_configured_final_field() {
        var source = StageTestSupport.attributedIndicators(
                StageTestSupport.indicator("https://same.example/a"),
                StageTestSupport.indicator("https://same.example/b"));
        DocumentProcessingPlan routing = occurrence -> Result.success(List.of(
                new RoutedArtifactCandidate("masks", new PreparedArtifactRow(
                        ArtifactRow.ordered(row("same.example", occurrence.tieOrdinal() == 0 ? "first" : "last")),
                        Optional.empty()))));
        var policy = new ArtifactWritePolicy(ArtifactWritePolicy.DuplicateSelection.LAST_NONEMPTY,
                "name", Map.of());
        var stage = new PrepareRoutedArtifactsStage(routing, List.of(empty("masks", "mask")),
                (artifact, row) -> Optional.of(new ArtifactRowKey(row.value("mask"))),
                Map.of("masks", policy), true);

        var output = stage.process(StageTestSupport.envelope(source, false)).payload();

        assertThat(output.plans().getFirst().rows()).singleElement()
                .satisfies(row -> assertThat(row.template().value("name")).isEqualTo("last"));
    }

    private static Map<String, String> row(String mask, String name) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("mask", mask);
        fields.put("name", name);
        return fields;
    }

    private static RoutedArtifactCandidate candidate(String artifact, String column,
                                                      String value, IndicatorOccurrence occurrence) {
        return new RoutedArtifactCandidate(artifact,
                new PreparedArtifactRow(ArtifactRow.ordered(Map.of(column, value)), Optional.empty(),
                        Map.of("name", occurrence.orderingPosition())));
    }

    private static ArtifactPreparer empty(String artifact, String column) {
        return new ArtifactPreparer() {
            @Override public String name() { return artifact; }

            @Override public Result<ArtifactWritePlan> prepare(
                    List<com.iocextractor.processing.model.ClassifiedIndicator> indicators) {
                return Result.success(new ArtifactWritePlan(artifact, List.of(column), List.of(),
                        new ArtifactIdSequence(ArtifactIdStrategy.ASCENDING, 1)));
            }
        };
    }
}
