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
import com.iocextractor.application.observation.OccurrencePosition;
import com.iocextractor.application.port.out.dataframeimport.ProcessedImportRowPreparer;
import com.iocextractor.diagnostics.DiagnosticFactory;
import com.iocextractor.domain.classify.ClassificationDecision;
import com.iocextractor.domain.extract.ExtractionOutcome;
import com.iocextractor.domain.extract.RawIndicator;
import com.iocextractor.domain.feature.HostKind;
import com.iocextractor.domain.feature.IndicatorFeatures;
import com.iocextractor.domain.model.Indicator;
import com.iocextractor.domain.model.IndicatorType;
import com.iocextractor.domain.model.MaskMatch;
import com.iocextractor.domain.model.SourceContext;
import com.iocextractor.domain.refang.RefangOutcome;
import com.iocextractor.processing.classification.IndicatorClassifier;
import com.iocextractor.processing.mapping.ArtifactFilter;
import com.iocextractor.processing.mapping.ColumnSpec;
import com.iocextractor.processing.mapping.ConfigurableRowMapper;
import com.iocextractor.processing.model.ClassifiedIndicator;
import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
    void urlAndIpContributeSeparateFieldsToOneCompoundRow() throws Exception {
        try (Fixture fixture = compoundFixture()) {
            var artifact = new DataframeImportCatalogDraft.Artifact("address_blacklist",
                    ImportArtifactRole.PRIMARY, "address-row-v2", List.of(), ImportMergePolicy.AUTHORITATIVE,
                    List.of(new DataframeImportCatalogDraft.Column("forbidden_url", "url", List.of(), null),
                            new DataframeImportCatalogDraft.Column("forbidden_ip", "ip", List.of(), null)));
            var result = fixture.mapper().map(withArtifacts(List.of(artifact)),
                    new ImportDelimitedRecord(30, Map.of("url", "hxxp://EVIL.example/drop",
                            "ip", "192.0.2.44")));

            assertThat(result.issues()).isEmpty();
            assertThat(result.row()).hasValueSatisfying(row -> {
                assertThat(row.branches()).hasSize(1);
                assertThat(row.branches().getFirst().cells())
                        .containsEntry("forbidden_url", ImportCell.value("http://EVIL.example/drop"))
                        .containsEntry("forbidden_ip", ImportCell.value("192.0.2.44"));
            });
            var explicitNull = fixture.mapper().map(withArtifacts(List.of(artifact)),
                    new ImportDelimitedRecord(31, Map.of("url", "NULL", "ip", "192.0.2.44")));
            assertThat(explicitNull.issues()).isEmpty();
            assertThat(explicitNull.row().orElseThrow().branches().getFirst().cells())
                    .containsEntry("forbidden_url", ImportCell.nullValue())
                    .containsEntry("forbidden_ip", ImportCell.value("192.0.2.44"));
        }
    }

    @Test
    void hostRouteClearsTheOldUrlCarrierBeforeResolvingCompositeIdentity() throws Exception {
        try (Fixture fixture = fixture(false, false, "address_blacklist", "host",
                List.of(new ColumnSpec("forbidden_url", "address.url", null, null, null),
                        new ColumnSpec("forbidden_ip", "address.ip", null, null, null)),
                List.of(new RouterProcessedImportRowPreparer.Input("address_blacklist", "forbidden_url")),
                Set.of("forbidden_url", "forbidden_ip"),
                List.of("forbidden_url", "forbidden_ip"), "address-row-v2")) {
            var artifact = new DataframeImportCatalogDraft.Artifact("address_blacklist",
                    ImportArtifactRole.PRIMARY, "address-row-v2", List.of(), ImportMergePolicy.AUTHORITATIVE,
                    List.of(new DataframeImportCatalogDraft.Column("forbidden_url", "url", List.of(), null),
                            new DataframeImportCatalogDraft.Column("forbidden_ip", "ip", List.of(), null)));
            var contract = withArtifacts(List.of(artifact));
            var first = fixture.mapper().map(contract, new ImportDelimitedRecord(31,
                    Map.of("url", "https://192.0.2.44/one")));
            var second = fixture.mapper().map(contract, new ImportDelimitedRecord(32,
                    Map.of("url", "https://192.0.2.44/two")));

            assertThat(first.issues()).isEmpty();
            assertThat(second.issues()).isEmpty();
            var firstBranch = first.row().orElseThrow().branches().getFirst();
            var secondBranch = second.row().orElseThrow().branches().getFirst();
            assertThat(firstBranch.cells()).containsEntry("forbidden_url", ImportCell.nullValue())
                    .containsEntry("forbidden_ip", ImportCell.value("192.0.2.44"));
            assertThat(secondBranch.cells()).containsEntry("forbidden_url", ImportCell.nullValue())
                    .containsEntry("forbidden_ip", ImportCell.value("192.0.2.44"));
            assertThat(firstBranch.recordKey()).isEqualTo(secondBranch.recordKey());
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

    @Test
    void equalDerivedValuesFromTwoInputsAssembleOneLogicalRow() throws Exception {
        try (Fixture fixture = fixture()) {
            var result = fixture.mapper().map(contract(), new ImportDelimitedRecord(7,
                    Map.of("ioc", "https://EVIL.example/one", "other", "http://evil.example/two")));

            assertThat(result.issues()).isEmpty();
            assertThat(result.row()).hasValueSatisfying(row -> {
                assertThat(row.branches()).hasSize(1);
                assertThat(row.branches().getFirst().cells())
                        .containsEntry("mask", ImportCell.value("evil.example"));
            });
        }
    }

    @Test
    void separateImportedUrlsResolveToTheSameFinalCanonicalKey() throws Exception {
        try (Fixture fixture = fixture()) {
            var first = fixture.mapper().map(contract(), new ImportDelimitedRecord(20,
                    Map.of("ioc", "https://EVIL.example/one")));
            var second = fixture.mapper().map(contract(), new ImportDelimitedRecord(21,
                    Map.of("ioc", "http://evil.example/two")));

            assertThat(first.issues()).isEmpty();
            assertThat(second.issues()).isEmpty();
            assertThat(first.row().orElseThrow().branches().getFirst().recordKey())
                    .isEqualTo(second.row().orElseThrow().branches().getFirst().recordKey());
            assertThat(first.row().orElseThrow().branches().getFirst().cells())
                    .containsEntry("mask", ImportCell.value("evil.example"));
            assertThat(second.row().orElseThrow().branches().getFirst().cells())
                    .containsEntry("mask", ImportCell.value("evil.example"));
        }
    }

    @Test
    void missingPrimaryInputRejectsTheWholeLogicalRow() throws Exception {
        try (Fixture fixture = fixture()) {
            var result = fixture.mapper().map(contract(), new ImportDelimitedRecord(8, Map.of()));

            assertThat(result.row()).isEmpty();
            assertThat(result.issues()).extracting(issue -> issue.code())
                    .containsExactly("IMPORT.PROCESSED_VALUE_UNROUTABLE");
        }
    }

    @Test
    void bindingRejectsUndeclaredContractsInputsAndOutputsBeforeRouting() throws Exception {
        try (Fixture fixture = fixture()) {
            var configured = contract();
            var source = new ImportDelimitedRecord(9, Map.of("ioc", "https://evil.example/path"));
            var admitted = fixture.mapper().admit(configured, source).row().orElseThrow();
            var input = new RouterProcessedImportRowPreparer.Input(ARTIFACT, "mask");

            assertThatThrownBy(() -> fixture.preparer("other", List.of(input),
                    Map.of(ARTIFACT, Set.of("mask"))).prepare(configured, source, admitted))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("pinned contract");
            assertThatThrownBy(() -> fixture.preparer(CONTRACT, List.of(input),
                    Map.of("unknown", Set.of("mask"))).prepare(configured, source, admitted))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("contract authority");
            assertThatThrownBy(() -> fixture.preparer(CONTRACT, List.of(input),
                    Map.of(ARTIFACT, Set.of("unknown"))).prepare(configured, source, admitted))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("output fields");
            assertThatThrownBy(() -> fixture.preparer(CONTRACT,
                    List.of(new RouterProcessedImportRowPreparer.Input(ARTIFACT, "unknown")),
                    Map.of(ARTIFACT, Set.of("mask"))).prepare(configured, source, admitted))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("input is not admitted");
            assertThatThrownBy(() -> fixture.preparer(CONTRACT,
                    List.of(new RouterProcessedImportRowPreparer.Input("unknown", "mask")),
                    Map.of(ARTIFACT, Set.of("mask"))).prepare(configured, source, admitted))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("input is not admitted");
        }
    }

    @Test
    void bindingRefusesOutputOmissionAndUnboundArtifacts() throws Exception {
        try (Fixture fixture = fixture()) {
            var configured = contract();
            var source = new ImportDelimitedRecord(10, Map.of("ioc", "https://evil.example/path"));
            var admitted = fixture.mapper().admit(configured, source).row().orElseThrow();
            var input = new RouterProcessedImportRowPreparer.Input(ARTIFACT, "mask");

            assertThatThrownBy(() -> fixture.preparer(CONTRACT, List.of(input),
                    Map.of(ARTIFACT, Set.of("alternate"))).prepare(configured, source, admitted))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("omitted bound import output");
            assertThatThrownBy(() -> fixture.preparer(CONTRACT, List.of(input),
                    Map.of()).prepare(configured, source, admitted))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("unauthorized import artifact");
        }
    }

    @Test
    void bindingConstructionRejectsAmbiguousOrUnboundedInputAndOutputDeclarations() throws Exception {
        try (Fixture fixture = fixture()) {
            var input = new RouterProcessedImportRowPreparer.Input(ARTIFACT, "mask");
            assertThatThrownBy(() -> new RouterProcessedImportRowPreparer.Input(" ", "mask"))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new RouterProcessedImportRowPreparer.Input(null, "mask"))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new RouterProcessedImportRowPreparer.Input(ARTIFACT, " "))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new RouterProcessedImportRowPreparer.Input(ARTIFACT, null))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> fixture.preparer(" ", List.of(input), Map.of()))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("contract ID");
            assertThatThrownBy(() -> fixture.preparer(null, List.of(input), Map.of()))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("contract ID");
            assertThatThrownBy(() -> fixture.preparer(CONTRACT, List.of(), Map.of()))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("bounded and unique");
            assertThatThrownBy(() -> fixture.preparer(CONTRACT, List.of(input, input), Map.of()))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("bounded and unique");
            var many = java.util.stream.IntStream.range(0, 33)
                    .mapToObj(index -> new RouterProcessedImportRowPreparer.Input(ARTIFACT, "field" + index))
                    .toList();
            assertThatThrownBy(() -> fixture.preparer(CONTRACT, many, Map.of()))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("bounded and unique");
            assertThatThrownBy(() -> fixture.preparer(CONTRACT, List.of(input), Map.of(" ", Set.of("mask"))))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("artifact and fields");
            assertThatThrownBy(() -> fixture.preparer(CONTRACT, List.of(input), Map.of(ARTIFACT, Set.of())))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("artifact and fields");
            assertThatThrownBy(() -> fixture.preparer(CONTRACT, List.of(input), Map.of(ARTIFACT, Set.of(" "))))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("non-blank");
            var nullArtifact = new HashMap<String, Set<String>>();
            nullArtifact.put(null, Set.of("mask"));
            assertThatThrownBy(() -> fixture.preparer(CONTRACT, List.of(input), nullArtifact))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("artifact and fields");
            var nullFields = new HashMap<String, Set<String>>();
            nullFields.put(ARTIFACT, null);
            assertThatThrownBy(() -> fixture.preparer(CONTRACT, List.of(input), nullFields))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("artifact and fields");
            var nullField = new java.util.HashSet<String>();
            nullField.add(null);
            assertThatThrownBy(() -> fixture.preparer(CONTRACT, List.of(input), Map.of(ARTIFACT, nullField)))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("non-blank");
        }
    }

    @Test
    void sourceAuthorityCannotBeAnOutputAndValueIsAttributedToTheRoute() throws Exception {
        try (Fixture fixture = fixture(false, false, "source.label")) {
            var configured = contractWithSourceLabel();
            var source = new ImportDelimitedRecord(11,
                    Map.of("ioc", "https://evil.example/path", "other", "Trusted Feed"));
            var admitted = fixture.mapper().admit(configured, source).row().orElseThrow();
            var input = new RouterProcessedImportRowPreparer.Input(ARTIFACT, "mask");

            assertThatThrownBy(() -> fixture.preparer(CONTRACT, List.of(input),
                    Map.of(ARTIFACT, Set.of("alternate"))).prepare(configured, source, admitted))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("source authority");

            var prepared = fixture.preparer(CONTRACT, List.of(input),
                    Map.of(ARTIFACT, Set.of("mask"))).prepare(configured, source, admitted);
            assertThat(prepared.issues()).isEmpty();
            assertThat(prepared.row()).hasValueSatisfying(row ->
                    assertThat(row.branches().getFirst().cells())
                            .containsEntry("mask", ImportCell.value("Trusted Feed")));

            var nullSource = new ImportDelimitedRecord(12,
                    Map.of("ioc", "https://evil.example/path", "other", "NULL"));
            var nullAdmitted = fixture.mapper().admit(configured, nullSource).row().orElseThrow();
            var withoutSource = fixture.preparer(CONTRACT, List.of(input),
                    Map.of(ARTIFACT, Set.of("mask"))).prepare(configured, nullSource, nullAdmitted);
            assertThat(withoutSource.row()).hasValueSatisfying(row ->
                    assertThat(row.branches().getFirst().cells())
                            .containsEntry("mask", ImportCell.value("https://evil.example/path")));
        }
    }

    @Test
    void compositionUsesSelectedAuthorityPolicyForThePinnedContract() throws Exception {
        try (Fixture fixture = fixture()) {
            ProcessedImportRowPreparer permissiveCompatible = new ProcessedImportRowPreparer() {
                @Override
                public com.iocextractor.application.dataframeimport.mapping.ImportRowMappingResult prepare(
                        CompiledDataframeImportContract contract, ImportDelimitedRecord record,
                        com.iocextractor.application.dataframeimport.model.ImportLogicalRow admitted) {
                    throw new AssertionError("Mapping is not exercised here");
                }

                @Override
                public boolean authorizesSourceLabel(CompiledDataframeImportContract contract,
                        String artifact, String target, ImportCell admitted, ImportCell prepared) {
                    return true;
                }
            };
            var selected = new SelectedProcessedImportRowPreparer(permissiveCompatible,
                    Map.of(CONTRACT, fixture.preparer(CONTRACT,
                            List.of(new RouterProcessedImportRowPreparer.Input(ARTIFACT, "mask")),
                            Map.of(ARTIFACT, Set.of("mask")))));

            assertThat(selected.authorizesSourceLabel(contract(), ARTIFACT, "source",
                    ImportCell.value("Trusted Feed"), ImportCell.value("forged"))).isFalse();
            assertThat(selected.authorizesSourceLabel(withContractId("compatible"), ARTIFACT, "source",
                    ImportCell.value("Trusted Feed"), ImportCell.value("forged"))).isTrue();
        }
    }

    private CompiledDataframeImportContract withContractId(String id) {
        var base = contract();
        return new CompiledDataframeImportContract(new ImportContractId(id), base.version(),
                base.definition(), base.dialect(), base.fingerprint());
    }

    @Test
    void relatedBranchWithoutASelectedOutputStaysInTheSameLogicalRow() throws Exception {
        try (Fixture fixture = fixture()) {
            var configured = contractWithRelatedArtifact();
            var source = new ImportDelimitedRecord(13,
                    Map.of("ioc", "https://evil.example/path", "note", "provenance"));
            var admitted = fixture.mapper().admit(configured, source).row().orElseThrow();
            var prepared = fixture.preparer(CONTRACT,
                    List.of(new RouterProcessedImportRowPreparer.Input(ARTIFACT, "mask")),
                    Map.of(ARTIFACT, Set.of("mask"))).prepare(configured, source, admitted);

            assertThat(prepared.issues()).isEmpty();
            assertThat(prepared.row()).hasValueSatisfying(row -> {
                assertThat(row.branches()).hasSize(2);
                assertThat(row.branches().get(1).cells())
                        .containsEntry("note", ImportCell.value("provenance"));
            });
        }
    }

    @Test
    void unrecoveredViewFailureRejectsTheRowAndDoesNotEmitAcceptedWarning() throws Exception {
        try (Fixture fixture = fixture(false, true, "value")) {
            var result = fixture.mapper().map(contract(),
                    new ImportDelimitedRecord(14, Map.of("ioc", "https://evil.example/path")));

            assertThat(result.row()).isEmpty();
            assertThat(result.warnings()).isEmpty();
            assertThat(result.issues()).extracting(issue -> issue.code())
                    .contains("IMPORT.PROCESSED_DERIVATION_FAILED");
        }
    }

    @Test
    void processingViewRejectsNegativeInvocationPosition() {
        var indicator = new Indicator("evil.example", IndicatorType.DOMAIN,
                new SourceContext(null, null));
        var classified = new ClassifiedIndicator(indicator, new ClassificationDecision(
                new IndicatorFeatures(indicator.value(), indicator.value(), false, false, false,
                        HostKind.REGISTRABLE), 0, List.of(), new MaskMatch(null, null)));

        assertThatThrownBy(() -> new ProcessingView(classified, new OccurrencePosition(1), -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ordinal");
    }

    Fixture fixture() {
        return fixture(false);
    }

    private Fixture fixture(boolean fallback) {
        return fixture(fallback, false, "value");
    }

    private Fixture fixture(boolean fallback, boolean failHost, String outputProvider) {
        return fixture(fallback, failHost, ARTIFACT, "host",
                List.of(new ColumnSpec("mask", outputProvider, null, null, null)),
                List.of(new RouterProcessedImportRowPreparer.Input(ARTIFACT, "mask"),
                        new RouterProcessedImportRowPreparer.Input(ARTIFACT, "alternate")),
                Set.of("mask"), List.of("mask"), "mask-row-v1");
    }

    private Fixture compoundFixture() {
        String artifact = "address_blacklist";
        return fixture(false, false, artifact, "original",
                List.of(new ColumnSpec("forbidden_url", "address.url", null, null, null),
                        new ColumnSpec("forbidden_ip", "address.ip", null, null, null)),
                List.of(new RouterProcessedImportRowPreparer.Input(artifact, "forbidden_url"),
                        new RouterProcessedImportRowPreparer.Input(artifact, "forbidden_ip")),
                Set.of("forbidden_url", "forbidden_ip"),
                List.of("forbidden_url", "forbidden_ip"), "address-row-v2");
    }

    private Fixture fixture(boolean fallback, boolean failHost, String artifact, String selectedView,
                            List<ColumnSpec> columns, List<RouterProcessedImportRowPreparer.Input> inputs,
                            Set<String> outputs, List<String> keyFields, String keyId) {
        var views = fallback ? List.of(
                new PlanDescriptor.View("host", "network.host", "original"),
                new PlanDescriptor.View("usable", "view.recover", "host",
                        new PlanDescriptor.Recovery("original", Set.of("NO_HOST"))))
                : List.of(new PlanDescriptor.View("host", "network.host", "original"));
        String defaultView = fallback ? "usable" : selectedView;
        var plan = new PlanDescriptor("import-host", views,
                new PlanDescriptor.Routing(PlanDescriptor.Mode.FIRST,
                        List.of(new PlanDescriptor.Branch("mask", artifact, null, List.of(defaultView))),
                        new PlanDescriptor.OnUnmatched(PlanDescriptor.Action.REJECT, null), null));
        var binding = new ProcessingPlanCatalog.CompiledPlan(plan,
                Map.of("mask", new ProcessingPlanCatalog.BranchBinding(artifact, defaultView, Map.of())),
                Set.of(defaultView));
        IndicatorClassifier classifier = new IndicatorClassifier(indicator ->
                new ClassificationDecision(new IndicatorFeatures(indicator.value(), indicator.value(),
                        false, false, false, indicator.type() == IndicatorType.IPV4
                                ? HostKind.IP : HostKind.REGISTRABLE), 0, List.of(),
                        new MaskMatch("u:hAS", "h:dAS")));
        var definition = new CsvArtifactDefinition(artifact,
                Set.of(IndicatorType.DOMAIN, IndicatorType.URL, IndicatorType.IPV4),
                ArtifactFilter.none(), new ConfigurableRowMapper(
                        columns,
                        ConfigRegistryCatalog.valueProviders(), Map.of()), ArtifactIdStrategy.ASCENDING, 1);
        var preparer = new CsvArtifactPreparer(definition,
                new ArtifactIdSequence(ArtifactIdStrategy.ASCENDING, 1),
                new DiagnosticFactory(Clock.systemUTC()), null, NoopPipelineDecisionTracer.INSTANCE);
        OperationCatalog registered = new IocProcessingOperations(binding,
                Map.of(artifact, preparer), classifier).catalog();
        OperationCatalog catalog = registered;
        if (fallback || failHost) {
            var operations = new HashMap<>(registered.operations());
            operations.put("network.host", exchange -> exchange.getMessage().setBody(
                    new ViewOutcome.Unavailable(new FailureReference("network.host", "NO_HOST"))));
            catalog = new OperationCatalog(operations, registered.destinations(),
                    registered.predicates(), Set.of("NO_HOST"));
        }
        var runtime = new CamelRouteRuntime(new CamelPlanCompiler().compile(List.of(plan), catalog));
        var route = new IocProcessingRouteAdapter(binding, runtime, Clock.systemUTC());
        var processed = new RouterProcessedImportRowPreparer(CONTRACT,
                inputs, Map.of(artifact, outputs),
                text -> new RefangOutcome(text.replace("hxxp", "http"), List.of()),
                text -> new ExtractionOutcome(List.of(new RawIndicator(text,
                        text.contains("://") ? IndicatorType.URL : IndicatorType.IPV4, 0)), List.of()),
                classifier, route);
        var keys = new CanonicalArtifactKeyResolver(List.of(new ArtifactIdentityDefinition(
                artifact, new CanonicalKeyDefinition(keyId, CanonicalKeyMode.COMPOSITE,
                        keyFields), List.of(), 1)));
        return new Fixture(new DataframeImportRowMapper((specification, value) -> value, keys, processed),
                runtime, route, classifier);
    }

    CompiledDataframeImportContract contract() {
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

    private CompiledDataframeImportContract contractWithSourceLabel() {
        var primary = contract().definition().artifacts().getFirst();
        var attributed = new DataframeImportCatalogDraft.Artifact(primary.name(), primary.role(),
                primary.recordKey(), primary.matchKeys(), primary.mergeDefault(), "alternate",
                null, primary.columns());
        return withArtifacts(List.of(attributed));
    }

    private CompiledDataframeImportContract contractWithRelatedArtifact() {
        var primary = contract().definition().artifacts().getFirst();
        var related = new DataframeImportCatalogDraft.Artifact("secondary", ImportArtifactRole.RELATED,
                "secondary-row-v1", List.of(), ImportMergePolicy.AUTHORITATIVE,
                List.of(new DataframeImportCatalogDraft.Column("note", "note", List.of(), null)));
        return withArtifacts(List.of(primary, related));
    }

    private CompiledDataframeImportContract withArtifacts(List<DataframeImportCatalogDraft.Artifact> artifacts) {
        var base = contract();
        var definition = base.definition();
        var changed = new DataframeImportCatalogDraft.Contract(definition.id(), definition.version(),
                definition.charset(), definition.dialect(), definition.recognition(), definition.mode(),
                definition.routing(), definition.rowFailurePolicy(), definition.duplicatePolicy(),
                definition.duplicateSelectionColumn(), definition.renewUnchanged(),
                definition.formulaPolicy(), definition.mergeDefault(), artifacts, definition.requestedSlot());
        return new CompiledDataframeImportContract(base.id(), base.version(), changed,
                base.dialect(), base.fingerprint());
    }

    record Fixture(DataframeImportRowMapper mapper, CamelRouteRuntime runtime,
                           IocProcessingRouteAdapter route, IndicatorClassifier classifier)
            implements AutoCloseable {
        private RouterProcessedImportRowPreparer preparer(String contractId,
                List<RouterProcessedImportRowPreparer.Input> inputs,
                Map<String, Set<String>> outputs) {
            return new RouterProcessedImportRowPreparer(contractId, inputs, outputs,
                    text -> new RefangOutcome(text.replace("hxxp", "http"), List.of()),
                    text -> new ExtractionOutcome(List.of(new RawIndicator(text, IndicatorType.URL, 0)),
                            List.of()), classifier, route);
        }

        @Override
        public void close() throws java.io.IOException {
            runtime.close();
        }
    }
}
