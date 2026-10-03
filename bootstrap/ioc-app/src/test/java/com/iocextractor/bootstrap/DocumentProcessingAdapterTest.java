package com.iocextractor.bootstrap;

import com.iocextractor.adapter.out.sink.csv.CsvArtifactDefinition;
import com.iocextractor.adapter.out.sink.csv.CsvArtifactPreparer;
import com.iocextractor.adapter.processing.camel.contract.Condition;
import com.iocextractor.adapter.processing.camel.contract.PlanDescriptor;
import com.iocextractor.adapter.processing.camel.compile.CamelPlanCompiler;
import com.iocextractor.adapter.processing.camel.compile.OperationCatalog;
import com.iocextractor.adapter.processing.camel.contract.BranchOutcome;
import com.iocextractor.adapter.processing.camel.contract.FailureReference;
import com.iocextractor.adapter.processing.camel.runtime.CamelRouteRuntime;
import com.iocextractor.application.artifact.ArtifactIdSequence;
import com.iocextractor.application.artifact.ArtifactIdStrategy;
import com.iocextractor.application.observability.NoopPipelineDecisionTracer;
import com.iocextractor.application.pipeline.payload.IndicatorOccurrence;
import com.iocextractor.diagnostics.DiagnosticFactory;
import com.iocextractor.diagnostics.DiagnosticSeverity;
import com.iocextractor.diagnostics.codes.PipelineDiagnosticCodes;
import com.iocextractor.domain.classify.ClassificationDecision;
import com.iocextractor.domain.classify.MatchPolicy;
import com.iocextractor.domain.feature.HostKind;
import com.iocextractor.domain.feature.IndicatorFeatures;
import com.iocextractor.domain.model.Indicator;
import com.iocextractor.domain.model.IndicatorType;
import com.iocextractor.domain.model.MaskMatch;
import com.iocextractor.domain.model.SourceContext;
import com.iocextractor.processing.mapping.ArtifactFilter;
import com.iocextractor.processing.mapping.ColumnSpec;
import com.iocextractor.processing.mapping.ConfigurableRowMapper;
import com.iocextractor.processing.mapping.MappingValueException;
import com.iocextractor.processing.classification.IndicatorClassifier;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.assertj.core.api.Assertions.assertThat;

@Timeout(15)
class DocumentProcessingAdapterTest {
    @Test
    void shared_runtime_uses_each_plan_binding_and_document_run_preparer() throws Exception {
        var original = plan(PlanDescriptor.Mode.FIRST,
                new PlanDescriptor.Branch("original-branch", "masks", null, List.of("original")));
        var derived = new PlanDescriptor("derived", original.views(),
                new PlanDescriptor.Routing(PlanDescriptor.Mode.FIRST,
                        List.of(new PlanDescriptor.Branch("host-branch", "masks", null,
                                List.of("host"))), original.routing().onUnmatched(), null));
        var originalBinding = new ProcessingPlanCatalog.CompiledPlan(original,
                Map.of("original-branch", new ProcessingPlanCatalog.BranchBinding(
                        "masks", "original", Map.of())), Set.of("original"));
        var hostBinding = new ProcessingPlanCatalog.CompiledPlan(derived,
                Map.of("host-branch", new ProcessingPlanCatalog.BranchBinding(
                        "masks", "host", Map.of())), Set.of("host"));
        var classifier = new IndicatorClassifier(indicator -> decision(indicator.value(), "configured"));
        var baseline = preparer("masks", List.of(column("mask", "value")));
        var runPreparer = new CsvArtifactPreparer(new CsvArtifactDefinition("masks",
                Set.of(IndicatorType.URL, IndicatorType.DOMAIN), ArtifactFilter.none(),
                new ConfigurableRowMapper(List.of(column("mask", "value")),
                        ConfigRegistryCatalog.valueProviders(), Map.of()),
                ArtifactIdStrategy.ASCENDING, 1),
                new ArtifactIdSequence(ArtifactIdStrategy.ASCENDING, 1),
                new DiagnosticFactory(Clock.systemUTC()), "document-source-key",
                NoopPipelineDecisionTracer.INSTANCE);
        var catalog = new IocProcessingOperations(Map.of("document", originalBinding,
                "derived", hostBinding), Map.of("masks", baseline), classifier).catalog();
        try (var runtime = new CamelRouteRuntime(new CamelPlanCompiler().compile(
                List.of(original, derived), catalog))) {
            var first = new DocumentProcessingAdapter(originalBinding, runtime, classifier,
                    Clock.systemUTC(), List.of(runPreparer));
            var second = new DocumentProcessingAdapter(hostBinding, runtime, classifier,
                    Clock.systemUTC(), Map.of("masks", runPreparer));
            var occurrence = occurrence("https://example.com/a", 1);

            assertThat(first.prepare(occurrence).value().getFirst().row().template().value("mask"))
                    .isEqualTo("https://example.com/a");
            assertThat(second.prepare(occurrence).value().getFirst().row().template().value("mask"))
                    .isEqualTo("example.com");
            assertThat(second.prepare(occurrence).value().getFirst().row().template().value("_source_key"))
                    .isEqualTo("document-source-key");
        }
    }

    @Test
    void documentSessionReusesSemanticsWithoutSkippingMappingOrOccurrenceMetadata() throws Exception {
        var descriptor = plan(PlanDescriptor.Mode.FIRST,
                new PlanDescriptor.Branch("mask", "masks", null, List.of("host")));
        var binding = new ProcessingPlanCatalog.CompiledPlan(descriptor,
                Map.of("mask", new ProcessingPlanCatalog.BranchBinding("masks", "host", Map.of())),
                Set.of("host"));
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        MatchPolicy policy = indicator -> {
            calls.incrementAndGet();
            return decision(indicator.value(), indicator.source().label());
        };
        var originalClassifier = new IndicatorClassifier(policy);
        var operationClassifier = new IndicatorClassifier(policy);
        var writePolicy = new com.iocextractor.application.artifact.policy.ArtifactWritePolicy(
                com.iocextractor.application.artifact.policy.ArtifactWritePolicy.DuplicateSelection.LAST_NONEMPTY,
                "source", Map.of("source", com.iocextractor.application.artifact.policy.ArtifactWritePolicy
                        .FieldUpdatePolicy.LATEST_REGISTERED_KEEP_EXISTING));
        var definition = new CsvArtifactDefinition("masks", Set.of(IndicatorType.DOMAIN), ArtifactFilter.none(),
                new ConfigurableRowMapper(List.of(column("mask", "value"), column("source", "source.label")),
                        ConfigRegistryCatalog.valueProviders(), Map.of()), ArtifactIdStrategy.ASCENDING, 1, writePolicy);
        var rows = new CsvArtifactPreparer(definition, new ArtifactIdSequence(ArtifactIdStrategy.ASCENDING, 1),
                new DiagnosticFactory(Clock.systemUTC()), "document", NoopPipelineDecisionTracer.INSTANCE);
        var operations = new IocProcessingOperations(binding, Map.of("masks", rows), operationClassifier).catalog();
        try (var runtime = new CamelRouteRuntime(new CamelPlanCompiler().compile(List.of(descriptor), operations))) {
            var adapter = new DocumentProcessingAdapter(binding, runtime, originalClassifier, Clock.systemUTC());
            try (var session = adapter.openSession()) {
                for (int ordinal = 0; ordinal < 3; ordinal++) {
                    var indicator = new Indicator("same.example", IndicatorType.DOMAIN,
                            new SourceContext(ordinal == 2 ? "second" : "first", null));
                    var occurrence = new IndicatorOccurrence(indicator,
                            ordinal + 1, ordinal);
                    var result = session.prepare(occurrence);
                    assertThat(result.diagnostics()).isEmpty();
                    assertThat(result.value()).singleElement().satisfies(candidate -> {
                        assertThat(candidate.row().template().value("mask")).isEqualTo("same.example");
                        assertThat(candidate.row().template().value("source")).isEqualTo(indicator.source().label());
                        assertThat(candidate.row().orderedFieldPositions())
                                .containsEntry("source", occurrence.orderingPosition());
                    });
                }
                assertThat(calls).hasValue(2);
            }
            adapter.prepare(occurrence("same.example", 4));
            assertThat(calls).hasValue(4);
        }
    }

    @Test
    void host_branch_merges_final_mask_value_while_original_branch_keeps_full_url() throws Exception {
        var plan = plan(PlanDescriptor.Mode.ALL,
                new PlanDescriptor.Branch("mask", "masks", typeIn("original", "URL"), List.of("host", "original")),
                new PlanDescriptor.Branch("blacklist", "address_blacklist",
                        typeIn("original", "URL"), List.of("original")));
        var binding = new ProcessingPlanCatalog.CompiledPlan(plan,
                Map.of("mask", new ProcessingPlanCatalog.BranchBinding("masks", "host",
                                Map.of("url_match", "original")),
                        "blacklist", new ProcessingPlanCatalog.BranchBinding(
                                "address_blacklist", "original", Map.of())), Set.of("original", "host"));
        try (var adapter = harness(binding,
                Map.of("masks", preparer("masks", List.of(
                                column("mask", "value"), column("url_match", "match.url"),
                                column("source", "source.label"))),
                        "address_blacklist", preparer("address_blacklist",
                                List.of(column("forbidden_url", "value")))),
                indicator -> decision(indicator.value(), indicator.value().contains("/")
                        ? "u:hEX,dEX" : "u:hAS"))) {
            var first = adapter.prepare(occurrence("https://best-malware.com/a", 1));
            var second = adapter.prepare(occurrence("https://best-malware.com/b", 2));

            assertThat(first.diagnostics()).isEmpty();
            assertThat(second.diagnostics()).isEmpty();
            assertThat(first.value()).extracting(candidate -> candidate.row().template().value("mask"))
                    .containsExactly("best-malware.com", null);
            assertThat(first.value().getFirst().row().template().value("url_match"))
                    .isEqualTo("u:hEX,dEX");
            assertThat(first.value().getFirst().row().template().value("source"))
                    .isEqualTo("feed");
            assertThat(second.value().getFirst().row().template().value("mask"))
                    .isEqualTo("best-malware.com");
            assertThat(first.value().get(1).row().template().value("forbidden_url"))
                    .isEqualTo("https://best-malware.com/a");
            assertThat(second.value().get(1).row().template().value("forbidden_url"))
                    .isEqualTo("https://best-malware.com/b");
        }
    }

    @Test
    void recovered_view_emits_only_warning_and_unrecovered_view_emits_error() throws Exception {
        var recovery = new PlanDescriptor.View("safe", "view.recover", "host",
                new PlanDescriptor.Recovery("original", Set.of("unsupported-scheme")));
        var branch = new PlanDescriptor.Branch("mask", "masks", null, List.of("safe"));
        var plan = new PlanDescriptor("recovery", List.of(
                new PlanDescriptor.View("host", "network.host", "original"), recovery),
                new PlanDescriptor.Routing(PlanDescriptor.Mode.FIRST, List.of(branch),
                        new PlanDescriptor.OnUnmatched(PlanDescriptor.Action.SKIP, null), null));
        var binding = new ProcessingPlanCatalog.CompiledPlan(plan,
                Map.of("mask", new ProcessingPlanCatalog.BranchBinding("masks", "safe", Map.of())),
                Set.of("safe"));
        try (var adapter = harness(binding,
                Map.of("masks", preparer("masks", List.of(column("mask", "value")))),
                indicator -> decision(indicator.value(), "configured"))) {
            var recovered = adapter.prepare(occurrence("ftp://bad.example/a", 1));
            assertThat(recovered.value()).hasSize(1);
            assertThat(recovered.diagnostics()).singleElement().satisfies(diagnostic -> {
                assertThat(diagnostic.code()).isEqualTo(PipelineDiagnosticCodes.VIEW_UNAVAILABLE);
                assertThat(diagnostic.severity()).isEqualTo(DiagnosticSeverity.WARN);
            });
            var unrecovered = adapter.prepare(occurrence("https://bad_host.example/a", 2));
            assertThat(unrecovered.value()).isEmpty();
            assertThat(unrecovered.diagnostics()).anySatisfy(diagnostic -> {
                assertThat(diagnostic.code()).isEqualTo(PipelineDiagnosticCodes.VIEW_UNAVAILABLE);
                assertThat(diagnostic.severity()).isEqualTo(DiagnosticSeverity.ERROR);
            });
        }
    }

    @Test
    void explicit_unmatched_reject_is_an_element_error() throws Exception {
        var plan = new PlanDescriptor("reject", List.of(), new PlanDescriptor.Routing(
                PlanDescriptor.Mode.FIRST,
                List.of(new PlanDescriptor.Branch("mask", "masks", typeIn("original", "DOMAIN"),
                        List.of("original"))),
                new PlanDescriptor.OnUnmatched(PlanDescriptor.Action.REJECT, null), null));
        var binding = new ProcessingPlanCatalog.CompiledPlan(plan,
                Map.of("mask", new ProcessingPlanCatalog.BranchBinding("masks", "original", Map.of())),
                Set.of("original"));
        try (var adapter = harness(binding,
                Map.of("masks", preparer("masks", List.of(column("mask", "value")))),
                indicator -> decision(indicator.value(), "configured"))) {
            var result = adapter.prepare(occurrence("https://example.com/a", 3));
            assertThat(result.value()).isEmpty();
            assertThat(result.diagnostics()).singleElement().satisfies(diagnostic -> {
                assertThat(diagnostic.code()).isEqualTo(PipelineDiagnosticCodes.ROUTING_REJECTED);
                assertThat(diagnostic.severity()).isEqualTo(DiagnosticSeverity.ERROR);
            });
        }
    }

    @Test
    void exclusive_multiple_matches_rejects_before_any_destination() throws Exception {
        var plan = plan(PlanDescriptor.Mode.EXCLUSIVE,
                new PlanDescriptor.Branch("first", "masks", typeIn("original", "URL"),
                        List.of("original")),
                new PlanDescriptor.Branch("second", "masks", typeIn("original", "URL"),
                        List.of("original")));
        var binding = new ProcessingPlanCatalog.CompiledPlan(plan,
                Map.of("first", new ProcessingPlanCatalog.BranchBinding("masks", "original", Map.of()),
                        "second", new ProcessingPlanCatalog.BranchBinding("masks", "original", Map.of())),
                Set.of("original"));
        try (var adapter = harness(binding,
                Map.of("masks", preparer("masks", List.of(column("mask", "value")))),
                indicator -> decision(indicator.value(), "configured"))) {
            var result = adapter.prepare(occurrence("https://example.com/a", 4));
            assertThat(result.value()).isEmpty();
            assertThat(result.diagnostics()).singleElement().satisfies(diagnostic ->
                    assertThat(diagnostic.context()).containsEntry("reason", "AMBIGUOUS"));
        }
    }

    @Test
    void selected_artifact_filter_is_a_non_error_and_mapping_failure_keeps_its_diagnostic()
            throws Exception {
        var plan = new PlanDescriptor("rows", List.of(), new PlanDescriptor.Routing(
                PlanDescriptor.Mode.FIRST,
                List.of(new PlanDescriptor.Branch("mask", "masks", null, List.of("original"))),
                new PlanDescriptor.OnUnmatched(PlanDescriptor.Action.SKIP, null), null));
        var binding = new ProcessingPlanCatalog.CompiledPlan(plan,
                Map.of("mask", new ProcessingPlanCatalog.BranchBinding("masks", "original", Map.of())),
                Set.of("original"));
        var filteredDefinition = new CsvArtifactDefinition("masks", Set.of(IndicatorType.DOMAIN),
                ArtifactFilter.none(), new ConfigurableRowMapper(
                        List.of(column("mask", "value")), ConfigRegistryCatalog.valueProviders(), Map.of()),
                ArtifactIdStrategy.ASCENDING, 1);
        var filtered = new CsvArtifactPreparer(filteredDefinition,
                new ArtifactIdSequence(ArtifactIdStrategy.ASCENDING, 1),
                new DiagnosticFactory(Clock.systemUTC()), null, NoopPipelineDecisionTracer.INSTANCE);
        try (var adapter = harness(binding, Map.of("masks", filtered),
                indicator -> decision(indicator.value(), "configured"))) {
            var result = adapter.prepare(occurrence("https://example.com/a", 5));
            assertThat(result.value()).isEmpty();
            assertThat(result.diagnostics()).isEmpty();
        }

        var mapping = new ConfigurableRowMapper(List.of(column("mask", "checked")),
                Map.of("checked", ignored -> { throw new MappingValueException("bad IOC"); }), Map.of());
        var mappingDefinition = new CsvArtifactDefinition("masks", Set.of(IndicatorType.URL),
                ArtifactFilter.none(), mapping, ArtifactIdStrategy.ASCENDING, 1);
        var failing = new CsvArtifactPreparer(mappingDefinition,
                new ArtifactIdSequence(ArtifactIdStrategy.ASCENDING, 1),
                new DiagnosticFactory(Clock.systemUTC()), null, NoopPipelineDecisionTracer.INSTANCE);
        try (var adapter = harness(binding, Map.of("masks", failing),
                indicator -> decision(indicator.value(), "configured"))) {
            var result = adapter.prepare(occurrence("https://example.com/a", 6));
            assertThat(result.value()).isEmpty();
            assertThat(result.diagnostics()).singleElement().satisfies(diagnostic ->
                    assertThat(diagnostic.context()).containsEntry("column", "mask"));
        }
    }

    @Test
    void technical_destination_unavailability_without_mapping_evidence_is_an_error() throws Exception {
        var plan = new PlanDescriptor("unavailable", List.of(), new PlanDescriptor.Routing(
                PlanDescriptor.Mode.FIRST,
                List.of(new PlanDescriptor.Branch("branch", "masks", null, List.of("original"))),
                new PlanDescriptor.OnUnmatched(PlanDescriptor.Action.SKIP, null), null));
        var binding = new ProcessingPlanCatalog.CompiledPlan(plan,
                Map.of("branch", new ProcessingPlanCatalog.BranchBinding("masks", "original", Map.of())),
                Set.of("original"));
        var catalog = new OperationCatalog(Map.of(), Map.of("masks", exchange ->
                exchange.getMessage().setBody(new BranchOutcome.Unavailable(
                        new FailureReference("branch", "UNAVAILABLE")))), Map.of());
        try (var runtime = new CamelRouteRuntime(new CamelPlanCompiler()
                .compile(List.of(plan), catalog))) {
            var adapter = new DocumentProcessingAdapter(binding, runtime,
                    new IndicatorClassifier(indicator -> decision(indicator.value(), "configured")),
                    Clock.systemUTC());
            var result = adapter.prepare(occurrence("https://example.com/a", 7));
            assertThat(result.diagnostics()).singleElement().satisfies(diagnostic -> {
                assertThat(diagnostic.code()).isEqualTo(PipelineDiagnosticCodes.VIEW_UNAVAILABLE);
                assertThat(diagnostic.severity()).isEqualTo(DiagnosticSeverity.ERROR);
            });
        }
    }

    private static CsvArtifactPreparer preparer(String name, List<ColumnSpec> columns) {
        var definition = new CsvArtifactDefinition(name,
                Set.of(IndicatorType.URL, IndicatorType.DOMAIN), ArtifactFilter.none(),
                new ConfigurableRowMapper(columns, ConfigRegistryCatalog.valueProviders(), Map.of()),
                ArtifactIdStrategy.ASCENDING, 1);
        return new CsvArtifactPreparer(definition,
                new ArtifactIdSequence(ArtifactIdStrategy.ASCENDING, 1),
                new DiagnosticFactory(Clock.systemUTC()), null,
                NoopPipelineDecisionTracer.INSTANCE);
    }

    private static Harness harness(ProcessingPlanCatalog.CompiledPlan plan,
                                   Map<String, CsvArtifactPreparer> preparers, MatchPolicy policy) {
        var classifier = new IndicatorClassifier(policy);
        var catalog = new IocProcessingOperations(plan, preparers, classifier).catalog();
        var runtime = new CamelRouteRuntime(new CamelPlanCompiler().compile(List.of(plan.router()), catalog));
        return new Harness(new DocumentProcessingAdapter(plan, runtime, classifier, Clock.systemUTC()), runtime);
    }

    private record Harness(DocumentProcessingAdapter adapter, CamelRouteRuntime runtime)
            implements AutoCloseable {
        com.iocextractor.diagnostics.result.Result<
                List<com.iocextractor.application.artifact.RoutedArtifactCandidate>> prepare(
                IndicatorOccurrence occurrence) {
            return adapter.prepare(occurrence);
        }

        @Override public void close() throws java.io.IOException { runtime.close(); }
    }

    private static ColumnSpec column(String name, String from) {
        return new ColumnSpec(name, from, null, null, null);
    }

    private static PlanDescriptor plan(PlanDescriptor.Mode mode, PlanDescriptor.Branch... branches) {
        return new PlanDescriptor("document", List.of(
                new PlanDescriptor.View("host", "network.host", "original")),
                new PlanDescriptor.Routing(mode, List.of(branches),
                        new PlanDescriptor.OnUnmatched(PlanDescriptor.Action.SKIP, null), null));
    }

    private static Condition typeIn(String view, String type) {
        return new Condition.Leaf(view, "type-in", Map.of("types", type));
    }

    private static IndicatorOccurrence occurrence(String value, int position) {
        return new IndicatorOccurrence(new Indicator(value, IndicatorType.URL,
                new SourceContext("feed", null)), position, position);
    }

    private static ClassificationDecision decision(String value, String code) {
        return new ClassificationDecision(new IndicatorFeatures(value, value,
                false, value.contains("/"), false, HostKind.UNKNOWN), -1, List.of(),
                new MaskMatch(code, null));
    }
}
