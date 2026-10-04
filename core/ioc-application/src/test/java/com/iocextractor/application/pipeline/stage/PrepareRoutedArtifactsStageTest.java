package com.iocextractor.application.pipeline.stage;

import com.iocextractor.application.pipeline.payload.AttributedIndicators;
import com.iocextractor.domain.extract.RawIndicator;
import com.iocextractor.domain.model.IndicatorType;
import com.iocextractor.domain.attribute.AttributionDecision;
import com.iocextractor.domain.attribute.AttributionOutcome;
import com.iocextractor.platform.etl.Envelope;
import com.iocextractor.diagnostics.DiagnosticSeverity;

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
    private static final ArtifactWritePolicy KEEP_FIRST = ArtifactWritePolicy.keepFirst();

    @Test
    void high_error_preparation_retains_bounded_detail_and_exact_outcome_and_delivery() {
        var error = StageTestSupport.DIAGNOSTICS.create(PipelineDiagnosticCodes.ROUTING_REJECTED)
                .with("plan", "test").with("indicator", "bad.example").with("reason", "REJECTED").build();
        DocumentProcessingPlan plan = occurrence -> Result.of(List.of(), List.of(error));
        var stage = new PrepareRoutedArtifactsStage(plan, List.of(empty("masks", "mask")),
                (artifact, row) -> Optional.empty(), Map.of("masks", KEEP_FIRST), false,
                StageTestSupport.DIAGNOSTICS, 3);
        var sink = new CollectingDiagnosticSink();
        var runner = new PipelineRunner(FailurePolicy.collectAndContinue(), new NoopPipelineObserver(),
                sink, StageTestSupport.DIAGNOSTICS, 3);

        var result = runner.runWithOutcome(manyOccurrences(100_000),
                Pipeline.<AttributedIndicators>start().then(stage));

        assertThat(result.envelope().payload().extracted()).isEqualTo(100_000);
        assertThat(result.envelope().diagnostics()).hasSize(4);
        assertThat(result.diagnosticSummary().total()).isEqualTo(100_000);
        assertThat(result.diagnosticSummary().suppressed()).isEqualTo(99_997);
        assertThat(result.diagnosticSummary().count(DiagnosticSeverity.ERROR))
                .isEqualTo(100_000);
        assertThat(sink.diagnostics()).containsExactlyElementsOf(result.envelope().diagnostics());
    }

    @Test
    void late_error_and_fatal_reject_before_id_reservation_and_durable_write() {
        for (var severity : List.of(DiagnosticSeverity.ERROR,
                DiagnosticSeverity.FATAL)) {
            var warning = StageTestSupport.DIAGNOSTICS.create(PipelineDiagnosticCodes.ITEM_SKIPPED)
                    .with("item", "sample").with("stage", "test").with("reason", "warning").build();
            var rejection = StageTestSupport.DIAGNOSTICS.create(PipelineDiagnosticCodes.ROUTING_REJECTED)
                    .severity(severity).with("plan", "test").with("indicator", "bad.example")
                    .with("reason", "late rejection").build();
            DocumentProcessingPlan plan = occurrence -> Result.of(List.of(),
                    List.of(occurrence.tieOrdinal() == 99_999 ? rejection : warning));
            var ids = new ArtifactIdSequence(ArtifactIdStrategy.ASCENDING, 17);
            var writes = new AtomicInteger();
            CanonicalArtifactRepository repository = new CanonicalArtifactRepository() {
                @Override public CanonicalArtifact load(String name) { throw new AssertionError("write checkpoint"); }
                @Override public CanonicalWriteResult write(String name, CanonicalArtifact artifact) {
                    writes.incrementAndGet();
                    return new CanonicalWriteResult(0, 0);
                }
            };
            var stage = new PrepareRoutedArtifactsStage(plan, List.of(empty("masks", "mask", ids)),
                    (artifact, row) -> Optional.empty(), Map.of("masks", KEEP_FIRST), false,
                    StageTestSupport.DIAGNOSTICS, 3);
            var sink = new CollectingDiagnosticSink();
            var policy = severity == DiagnosticSeverity.ERROR
                    ? FailurePolicy.failFast() : FailurePolicy.collectAndContinue();
            var runner = new PipelineRunner(policy, new NoopPipelineObserver(), sink, StageTestSupport.DIAGNOSTICS, 3);
            var pipeline = Pipeline.<AttributedIndicators>start()
                    .then(stage).then(new WriteArtifactsStage(repository,
                            ignored -> ArtifactProjectionResult.clean(0), StageTestSupport.DIAGNOSTICS));

            assertThatThrownBy(() -> runner.run(manyOccurrences(100_000), pipeline))
                    .isInstanceOf(DiagnosticException.class).extracting("diagnostic").isEqualTo(rejection);
            assertThat(writes).hasValue(0);
            assertThat(ids.reserve(1).start()).isEqualTo(17);
            assertThat(sink.diagnostics()).hasSize(5).containsOnlyOnce(rejection);
            assertThat(sink.diagnostics().getLast().context()).containsEntry("suppressedCount", 99_997L);
        }
    }

    @Test
    void duplicate_and_mapping_diagnostics_remain_counted_for_losing_occurrences() {
        var warning = StageTestSupport.DIAGNOSTICS.create(PipelineDiagnosticCodes.ITEM_SKIPPED)
                .with("item", "sample").with("stage", "mapping").with("reason", "warning").build();
        var mapped = new AtomicInteger();
        DocumentProcessingPlan plan = new DocumentProcessingPlan() {
            @Override public com.iocextractor.application.artifact.DocumentObservationSelection observationSelection() {
                return com.iocextractor.application.artifact.DocumentObservationSelection.RETAINED_OBSERVATIONS;
            }
            @Override public Result<List<RoutedArtifactCandidate>> prepare(IndicatorOccurrence occurrence) {
                mapped.incrementAndGet();
                return Result.of(List.of(candidate("masks", "mask", "same.example", occurrence)), List.of(warning));
            }
        };
        var stage = new PrepareRoutedArtifactsStage(plan, List.of(empty("masks", "mask")),
                (artifact, row) -> Optional.of(new ArtifactRowKey(row.value("mask"))),
                Map.of("masks", KEEP_FIRST), true, StageTestSupport.DIAGNOSTICS, 3);

        var output = stage.process(manyOccurrences(100_000));

        assertThat(output.payload().extracted()).isEqualTo(100_000);
        assertThat(output.payload().retained()).isOne();
        assertThat(output.payload().plans().getFirst().rows()).hasSize(1);
        assertThat(mapped).hasValue(100_000);
        assertThat(output.diagnostics()).hasSize(3);
        assertThat(output.diagnosticSummary().total()).isEqualTo(199_999);
        assertThat(output.diagnosticSummary().suppressed()).isEqualTo(199_996);
        assertThat(output.diagnostics()).extracting(diagnostic -> diagnostic.context().get("stage"))
                .containsExactly("mapping", StageNames.DEDUPLICATE.value(), "mapping");
    }

    private static Envelope<
            AttributedIndicators> manyOccurrences(int count) {
        var raw = new RawIndicator("same.example",
                IndicatorType.DOMAIN, 0);
        var decision = new AttributionDecision(raw, Optional.empty());
        var outcome = new AttributionOutcome(List.of(),
                java.util.Collections.nCopies(count, decision));
        return StageTestSupport.envelope(
                new AttributedIndicators(outcome), false);
    }

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
        var pipeline = Pipeline.<AttributedIndicators>start()
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

    @Test
    void preparationOwnsOneSessionAndClosesItOnSuccessAndFailure() {
        for (boolean fail : List.of(false, true)) {
            var opened = new AtomicInteger();
            var closed = new AtomicInteger();
            var visited = new AtomicInteger();
            DocumentProcessingPlan plan = new DocumentProcessingPlan() {
                @Override public Result<List<RoutedArtifactCandidate>> prepare(IndicatorOccurrence occurrence) {
                    throw new AssertionError("The stage must use its document session");
                }

                @Override public com.iocextractor.application.port.out.artifact.DocumentProcessingSession openSession() {
                    opened.incrementAndGet();
                    return new com.iocextractor.application.port.out.artifact.DocumentProcessingSession() {
                        @Override public Result<List<RoutedArtifactCandidate>> prepare(IndicatorOccurrence occurrence) {
                            visited.incrementAndGet();
                            if (fail) {
                                throw new IllegalStateException("operation defect");
                            }
                            return Result.success(List.of(candidate("masks", "mask", "same.example", occurrence)));
                        }
                        @Override public void close() { closed.incrementAndGet(); }
                    };
                }
            };
            var source = StageTestSupport.indicator("same.example");
            var input = StageTestSupport.envelope(StageTestSupport.attributedIndicators(source, source), false);
            var stage = new PrepareRoutedArtifactsStage(plan, List.of(empty("masks", "mask")),
                    (artifact, row) -> Optional.of(new ArtifactRowKey(row.value("mask"))),
                    Map.of("masks", KEEP_FIRST), false);
            if (fail) {
                assertThatThrownBy(() -> stage.process(input)).hasMessage("operation defect");
            } else {
                assertThat(stage.process(input).payload().plans().getFirst().rows()).hasSize(1);
            }
            assertThat(opened).hasValue(1);
            assertThat(closed).hasValue(1);
            assertThat(visited).hasValue(fail ? 1 : 2);
        }
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
