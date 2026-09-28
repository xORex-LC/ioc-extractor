package com.iocextractor.bootstrap;

import com.iocextractor.adapter.out.sink.csv.CsvArtifactDefinition;
import com.iocextractor.adapter.out.sink.csv.CsvArtifactPreparer;
import com.iocextractor.adapter.processing.camel.compile.CamelPlanCompiler;
import com.iocextractor.adapter.processing.camel.compile.OperationCatalog;
import com.iocextractor.adapter.processing.camel.contract.FailureReference;
import com.iocextractor.adapter.processing.camel.contract.PlanDescriptor;
import com.iocextractor.adapter.processing.camel.contract.ViewOutcome;
import com.iocextractor.adapter.processing.camel.runtime.CamelRouteRuntime;
import com.iocextractor.application.artifact.ArtifactIdSequence;
import com.iocextractor.application.artifact.ArtifactIdStrategy;
import com.iocextractor.application.artifact.ArtifactIdentityDefinition;
import com.iocextractor.application.artifact.CanonicalArtifactKeyResolver;
import com.iocextractor.application.artifact.CanonicalKeyDefinition;
import com.iocextractor.application.artifact.CanonicalKeyMode;
import com.iocextractor.application.dataframeimport.contract.CompiledDataframeImportContract;
import com.iocextractor.application.dataframeimport.contract.DataframeImportCatalogDraft;
import com.iocextractor.application.dataframeimport.mapping.DataframeImportRowMapper;
import com.iocextractor.application.dataframeimport.model.DelimitedDialect;
import com.iocextractor.application.dataframeimport.model.ImportArtifactRole;
import com.iocextractor.application.dataframeimport.model.ImportCell;
import com.iocextractor.application.dataframeimport.model.ImportContractFingerprint;
import com.iocextractor.application.dataframeimport.model.ImportContractId;
import com.iocextractor.application.dataframeimport.model.ImportDelimitedRecord;
import com.iocextractor.application.dataframeimport.model.ImportDuplicatePolicy;
import com.iocextractor.application.dataframeimport.model.ImportFormulaPolicy;
import com.iocextractor.application.dataframeimport.model.ImportMergePolicy;
import com.iocextractor.application.dataframeimport.model.ImportProcessingMode;
import com.iocextractor.application.dataframeimport.model.ImportRecordSeparator;
import com.iocextractor.application.dataframeimport.model.ImportRoutingPolicy;
import com.iocextractor.application.dataframeimport.model.ImportRowFailurePolicy;
import com.iocextractor.application.observability.NoopPipelineDecisionTracer;
import com.iocextractor.diagnostics.DiagnosticFactory;
import com.iocextractor.domain.classify.ClassificationDecision;
import com.iocextractor.domain.extract.ExtractionOutcome;
import com.iocextractor.domain.extract.RawIndicator;
import com.iocextractor.domain.feature.HostKind;
import com.iocextractor.domain.feature.IndicatorFeatures;
import com.iocextractor.domain.model.IndicatorType;
import com.iocextractor.domain.model.MaskMatch;
import com.iocextractor.domain.refang.RefangOutcome;
import com.iocextractor.processing.classification.IndicatorClassifier;
import com.iocextractor.processing.mapping.ArtifactFilter;
import com.iocextractor.processing.mapping.ColumnSpec;
import com.iocextractor.processing.mapping.ConfigurableRowMapper;
import java.time.Clock;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.assertj.core.api.Assertions.assertThat;

@Timeout(15)
class RouterProcessedImportRowPreparerTest {
    private static final String CONTRACT = "processed-masks";
    private static final String ARTIFACT = "masks";

    @Test
    void derivesFinalKeyFromHostAndPreservesAnAbsentOptionalCell() throws Exception {
        try (Fixture fixture = fixture()) {
            var result = fixture.mapper().map(contract(),
                    new ImportDelimitedRecord(2, Map.of("ioc", "hxxp://EVIL.example/drop")));

            assertThat(result.issues()).isEmpty();
            assertThat(result.row()).hasValueSatisfying(row -> {
                var branch = row.branches().getFirst();
                assertThat(branch.cells()).containsEntry("mask", ImportCell.value("evil.example"))
                        .containsEntry("alternate", ImportCell.absent());
                assertThat(branch.recordKey()).isPresent();
                assertThat(branch.recordKey().orElseThrow().keyCanonical()).contains("evil.example");
            });
        }
    }

    @Test
    void explicitNullOptionalCellDoesNotBecomeAnInputOrAnOutput() throws Exception {
        try (Fixture fixture = fixture()) {
            var result = fixture.mapper().map(contract(), new ImportDelimitedRecord(3,
                    Map.of("ioc", "https://evil.example/path", "other", "NULL")));

            assertThat(result.issues()).isEmpty();
            assertThat(result.row()).hasValueSatisfying(row -> assertThat(row.branches().getFirst().cells())
                    .containsEntry("alternate", ImportCell.nullValue()));
        }
    }

    @Test
    void twoBoundInputsWithDifferentFinalValuesRejectOneLogicalRow() throws Exception {
        try (Fixture fixture = fixture()) {
            var result = fixture.mapper().map(contract(), new ImportDelimitedRecord(4,
                    Map.of("ioc", "https://first.example/path",
                            "other", "https://second.example/path")));

            assertThat(result.row()).isEmpty();
            assertThat(result.issues()).extracting(issue -> issue.code())
                    .contains("IMPORT.PROCESSED_COMPOUND_CONFLICT");
        }
    }

    @Test
    void rejectsFreeTextInsteadOfUsingAValidAddressPrefix() throws Exception {
        try (Fixture fixture = fixture()) {
            var result = fixture.mapper().map(contract(), new ImportDelimitedRecord(5,
                    Map.of("ioc", "https://evil.example/path unrelated text")));

            assertThat(result.row()).isEmpty();
            assertThat(result.issues()).extracting(issue -> issue.code())
                    .contains("IMPORT.PROCESSED_INPUT_INVALID");
        }
    }

    @Test
    void recoveredViewFailureIsAnAcceptedWarningAndKeepsTheActualOriginalType() throws Exception {
        try (Fixture fixture = fixture(true)) {
            var result = fixture.mapper().map(contract(),
                    new ImportDelimitedRecord(6, Map.of("ioc", "hxxp://EVIL.example/drop")));

            assertThat(result.issues()).isEmpty();
            assertThat(result.warnings()).extracting(warning -> warning.code())
                    .containsExactly("IMPORT.PROCESSED_VIEW_FALLBACK");
            assertThat(result.row()).hasValueSatisfying(row -> assertThat(row.branches().getFirst().cells())
                    .containsEntry("mask", ImportCell.value("http://EVIL.example/drop")));
        }
    }

    private Fixture fixture() {
        return fixture(false);
    }

    private Fixture fixture(boolean fallback) {
        var views = fallback ? List.of(
                new PlanDescriptor.View("host", "network.host", "original"),
                new PlanDescriptor.View("usable", "view.recover", "host",
                        new PlanDescriptor.Recovery("original", Set.of("NO_HOST"))))
                : List.of(new PlanDescriptor.View("host", "network.host", "original"));
        String defaultView = fallback ? "usable" : "host";
        var plan = new PlanDescriptor("import-host", views,
                new PlanDescriptor.Routing(PlanDescriptor.Mode.FIRST,
                        List.of(new PlanDescriptor.Branch("mask", ARTIFACT, null, List.of(defaultView))),
                        new PlanDescriptor.OnUnmatched(PlanDescriptor.Action.REJECT, null), null));
        var binding = new ProcessingPlanCatalog.CompiledPlan(plan,
                Map.of("mask", new ProcessingPlanCatalog.BranchBinding(ARTIFACT, defaultView, Map.of())),
                Set.of(defaultView));
        IndicatorClassifier classifier = new IndicatorClassifier(indicator ->
                new ClassificationDecision(new IndicatorFeatures(indicator.value(), indicator.value(),
                        false, false, false, HostKind.REGISTRABLE), 0, List.of(),
                        new MaskMatch("u:hAS", "h:dAS")));
        var definition = new CsvArtifactDefinition(ARTIFACT,
                Set.of(IndicatorType.DOMAIN, IndicatorType.URL),
                ArtifactFilter.none(), new ConfigurableRowMapper(
                        List.of(new ColumnSpec("mask", "value", null, null, null)),
                        ConfigRegistryCatalog.valueProviders(), Map.of()), ArtifactIdStrategy.ASCENDING, 1);
        var preparer = new CsvArtifactPreparer(definition,
                new ArtifactIdSequence(ArtifactIdStrategy.ASCENDING, 1),
                new DiagnosticFactory(Clock.systemUTC()), null, NoopPipelineDecisionTracer.INSTANCE);
        OperationCatalog registered = new IocProcessingOperations(binding,
                Map.of(ARTIFACT, preparer), classifier).catalog();
        OperationCatalog catalog = registered;
        if (fallback) {
            var operations = new HashMap<>(registered.operations());
            operations.put("network.host", exchange -> exchange.getMessage().setBody(
                    new ViewOutcome.Unavailable(new FailureReference("network.host", "NO_HOST"))));
            catalog = new OperationCatalog(operations, registered.destinations(),
                    registered.predicates(), Set.of("NO_HOST"));
        }
        var runtime = new CamelRouteRuntime(new CamelPlanCompiler().compile(List.of(plan), catalog));
        var route = new IocProcessingRouteAdapter(binding, runtime, Clock.systemUTC());
        var processed = new RouterProcessedImportRowPreparer(CONTRACT,
                List.of(new RouterProcessedImportRowPreparer.Input(ARTIFACT, "mask"),
                        new RouterProcessedImportRowPreparer.Input(ARTIFACT, "alternate")),
                Map.of(ARTIFACT, Set.of("mask")),
                text -> new RefangOutcome(text.replace("hxxp", "http"), List.of()),
                text -> new ExtractionOutcome(List.of(new RawIndicator(text, IndicatorType.URL, 0)), List.of()),
                classifier, route);
        var keys = new CanonicalArtifactKeyResolver(List.of(new ArtifactIdentityDefinition(
                ARTIFACT, new CanonicalKeyDefinition("mask-row-v1", CanonicalKeyMode.COMPOSITE,
                        List.of("mask")), List.of(), 1)));
        return new Fixture(new DataframeImportRowMapper((specification, value) -> value, keys, processed), runtime);
    }

    private CompiledDataframeImportContract contract() {
        var artifact = new DataframeImportCatalogDraft.Artifact(ARTIFACT, ImportArtifactRole.PRIMARY,
                "mask-row-v1", List.of(), ImportMergePolicy.AUTHORITATIVE,
                List.of(new DataframeImportCatalogDraft.Column("mask", "ioc", List.of(), null),
                        new DataframeImportCatalogDraft.Column("alternate", "other", List.of(), null)));
        var definition = new DataframeImportCatalogDraft.Contract(CONTRACT, 1, "UTF-8",
                new DataframeImportCatalogDraft.Dialect(",", "\"", ImportRecordSeparator.LF, true, List.of("NULL")),
                new DataframeImportCatalogDraft.Recognition(List.of("ioc"), List.of("other"),
                        List.of(), Map.of()), ImportProcessingMode.PROCESSED,
                ImportRoutingPolicy.TARGET_ONLY, ImportRowFailurePolicy.REJECT_DELIVERY,
                ImportDuplicatePolicy.COALESCE, true, ImportFormulaPolicy.REJECT,
                ImportMergePolicy.AUTHORITATIVE, List.of(artifact), null);
        return new CompiledDataframeImportContract(new ImportContractId(CONTRACT), 1, definition,
                new DelimitedDialect(',', '"', ImportRecordSeparator.LF, true, List.of("NULL")),
                new ImportContractFingerprint("a".repeat(64)));
    }

    private record Fixture(DataframeImportRowMapper mapper, CamelRouteRuntime runtime) implements AutoCloseable {
        @Override
        public void close() throws java.io.IOException {
            runtime.close();
        }
    }
}
