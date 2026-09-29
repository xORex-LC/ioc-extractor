package com.iocextractor.application.pipeline.stage;

import com.iocextractor.application.artifact.ArtifactIdSequence;
import com.iocextractor.application.artifact.ArtifactIdStrategy;
import com.iocextractor.application.artifact.ArtifactRow;
import com.iocextractor.application.artifact.ArtifactRowKey;
import com.iocextractor.application.artifact.CanonicalArtifact;
import com.iocextractor.application.artifact.CanonicalWriteResult;
import com.iocextractor.application.artifact.ArtifactWritePlan;
import com.iocextractor.application.artifact.PreparedArtifactRow;
import com.iocextractor.application.artifact.RoutedArtifactCandidate;
import com.iocextractor.application.artifact.policy.ArtifactWritePolicy;
import com.iocextractor.application.pipeline.payload.IndicatorOccurrence;
import com.iocextractor.application.port.out.artifact.ArtifactPreparer;
import com.iocextractor.application.port.out.artifact.DocumentProcessingPlan;
import com.iocextractor.application.port.out.artifact.CanonicalArtifactRepository;
import com.iocextractor.application.port.out.artifact.ArtifactProjectionResult;
import com.iocextractor.diagnostics.Diagnostic;
import com.iocextractor.diagnostics.DiagnosticException;
import com.iocextractor.diagnostics.codes.PipelineDiagnosticCodes;
import com.iocextractor.diagnostics.result.FailurePolicy;
import com.iocextractor.diagnostics.result.Result;
import com.iocextractor.diagnostics.sink.CollectingDiagnosticSink;
import com.iocextractor.platform.etl.NoopPipelineObserver;
import com.iocextractor.platform.etl.Pipeline;
import com.iocextractor.platform.etl.PipelineRunner;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
    void synthetic_ip_and_country_identity_controls_multiplicity_without_a_new_artifact() {
        var ip = StageTestSupport.indicator("10.93.12.187");
        var source = StageTestSupport.attributedIndicators(ip, ip);
        DocumentProcessingPlan routing = occurrence -> Result.success(List.of(
                new RoutedArtifactCandidate("synthetic", new PreparedArtifactRow(
                        ArtifactRow.ordered(Map.of("ip", "10.93.12.187", "country",
                                occurrence.tieOrdinal() == 0 ? "A" : "B")), Optional.empty()))));
        var preparers = List.of(empty("synthetic", "ip"));
        var policies = Map.of("synthetic", KEEP_FIRST);
        var composite = new PrepareRoutedArtifactsStage(routing, preparers,
                (artifact, row) -> Optional.of(new ArtifactRowKey(
                        row.value("ip") + ":" + row.value("country"))), policies, true);
        var ipOnly = new PrepareRoutedArtifactsStage(routing, preparers,
                (artifact, row) -> Optional.of(new ArtifactRowKey(row.value("ip"))), policies, true);

        assertThat(composite.process(StageTestSupport.envelope(source, false)).payload()
                .plans().getFirst().rows())
                .extracting(row -> row.template().value("country"))
                .containsExactly("A", "B");
        assertThat(ipOnly.process(StageTestSupport.envelope(source, false)).payload()
                .plans().getFirst().rows())
                .extracting(row -> row.template().value("country"))
                .containsExactly("A");
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

    @Test
    void routed_rejection_reaches_checkpoint_before_ids_or_storage() {
        var ids = new ArtifactIdSequence(ArtifactIdStrategy.ASCENDING, 17);
        ArtifactPreparer preparer = empty("masks", "mask", ids);
        Diagnostic rejection = StageTestSupport.DIAGNOSTICS
                .create(PipelineDiagnosticCodes.ROUTING_REJECTED)
                .with("plan", "test").with("indicator", "bad.example")
                .with("reason", "REJECTED").build();
        DocumentProcessingPlan routing = occurrence -> Result.of(List.of(), List.of(rejection));
        var writes = new AtomicInteger();
        CanonicalArtifactRepository repository = new CanonicalArtifactRepository() {
            @Override public CanonicalArtifact load(String name) { throw new UnsupportedOperationException(); }
            @Override public CanonicalWriteResult write(String name, CanonicalArtifact artifact) {
                writes.incrementAndGet();
                return new CanonicalWriteResult(0, 0);
            }
        };
        var pipeline = Pipeline.<com.iocextractor.application.pipeline.payload.AttributedIndicators>start()
                .then(new PrepareRoutedArtifactsStage(routing, List.of(preparer),
                        (artifact, row) -> Optional.of(new ArtifactRowKey(row.value("mask"))),
                        Map.of("masks", KEEP_FIRST), true))
                .then(new WriteArtifactsStage(repository,
                        ignored -> ArtifactProjectionResult.clean(0), StageTestSupport.DIAGNOSTICS));
        var runner = new PipelineRunner(FailurePolicy.failFast(), new NoopPipelineObserver(),
                new CollectingDiagnosticSink(), StageTestSupport.DIAGNOSTICS);

        assertThatThrownBy(() -> runner.run(StageTestSupport.envelope(
                StageTestSupport.attributedIndicators(StageTestSupport.indicator("bad.example")), false),
                pipeline)).isInstanceOf(DiagnosticException.class);
        assertThat(writes).hasValue(0);
        assertThat(ids.reserve(1).start()).isEqualTo(17);
    }

    @Test
    void duplicate_originals_keep_occurrences_and_retained_count_follows_dedup_policy() {
        var same = StageTestSupport.indicator("https://same.example/a");
        var input = StageTestSupport.envelope(StageTestSupport.attributedIndicators(same, same), false);
        DocumentProcessingPlan routing = occurrence -> Result.success(List.of(
                candidate("masks", "mask", "same.example", occurrence)));
        var preparers = List.of(empty("masks", "mask"));
        var identity = (com.iocextractor.application.port.out.artifact.ArtifactIdentityResolver)
                (artifact, row) -> Optional.of(new ArtifactRowKey(row.value("mask")));

        assertThat(new PrepareRoutedArtifactsStage(routing, preparers, identity,
                Map.of("masks", KEEP_FIRST), true).process(input).payload().retained()).isEqualTo(1);
        assertThat(new PrepareRoutedArtifactsStage(routing, preparers, identity,
                Map.of("masks", KEEP_FIRST), false).process(input).payload().retained()).isEqualTo(2);
    }

    @Test
    void a_plan_cannot_route_to_an_artifact_outside_the_enabled_write_set() {
        var input = StageTestSupport.envelope(StageTestSupport.attributedIndicators(
                StageTestSupport.indicator("example.com")), false);
        DocumentProcessingPlan routing = occurrence -> Result.success(List.of(
                candidate("unknown", "mask", "example.com", occurrence)));
        var stage = new PrepareRoutedArtifactsStage(routing, List.of(empty("masks", "mask")),
                (artifact, row) -> Optional.of(new ArtifactRowKey(row.value("mask"))),
                Map.of("masks", KEEP_FIRST), true);

        assertThatThrownBy(() -> stage.process(input)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unknown artifact");
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
        return empty(artifact, column, new ArtifactIdSequence(ArtifactIdStrategy.ASCENDING, 1));
    }

    private static ArtifactPreparer empty(String artifact, String column, ArtifactIdSequence ids) {
        return new ArtifactPreparer() {
            @Override public String name() { return artifact; }

            @Override public Result<ArtifactWritePlan> prepare(
                    List<com.iocextractor.processing.model.ClassifiedIndicator> indicators) {
                return Result.success(new ArtifactWritePlan(artifact, List.of(column), List.of(),
                        ids));
            }
        };
    }
}
