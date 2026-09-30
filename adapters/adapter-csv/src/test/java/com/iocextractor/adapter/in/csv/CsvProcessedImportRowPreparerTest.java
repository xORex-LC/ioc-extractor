package com.iocextractor.adapter.in.csv;


import com.iocextractor.processing.mapping.AddressIpValueProvider;
import com.iocextractor.processing.mapping.AddressUrlValueProvider;
import com.iocextractor.processing.mapping.ArtifactFilter;
import com.iocextractor.processing.mapping.ColumnSpec;
import com.iocextractor.processing.mapping.ConfigurableRowMapper;
import com.iocextractor.adapter.out.sink.csv.CsvArtifactDefinition;
import com.iocextractor.adapter.out.sink.csv.IdValueProvider;
import com.iocextractor.processing.mapping.IndicatorValueProvider;
import com.iocextractor.processing.mapping.LowerHostTransform;
import com.iocextractor.processing.mapping.MatchHostValueProvider;
import com.iocextractor.processing.mapping.MatchUrlValueProvider;
import com.iocextractor.processing.mapping.MappingValueException;
import com.iocextractor.processing.mapping.SourceLabelValueProvider;
import com.iocextractor.processing.mapping.StripPrefixTransform;
import com.iocextractor.processing.mapping.ValueProvider;
import com.iocextractor.application.artifact.ArtifactIdStrategy;
import com.iocextractor.application.artifact.ArtifactIdentityDefinition;
import com.iocextractor.application.artifact.CanonicalArtifactKeyResolver;
import com.iocextractor.application.artifact.CanonicalKeyDefinition;
import com.iocextractor.application.artifact.CanonicalKeyMode;
import com.iocextractor.processing.classification.IndicatorClassifier;
import com.iocextractor.processing.model.ClassifiedIndicator;
import com.iocextractor.application.dataframeimport.contract.CompiledDataframeImportContract;
import com.iocextractor.application.dataframeimport.contract.DataframeImportCatalogDraft;
import com.iocextractor.application.dataframeimport.mapping.DataframeImportRowMapper;
import com.iocextractor.application.dataframeimport.mapping.ImportRowMappingResult;
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
import com.iocextractor.domain.classify.ClassificationDecision;
import com.iocextractor.domain.extract.ExtractionOutcome;
import com.iocextractor.domain.extract.RawIndicator;
import com.iocextractor.domain.feature.HostKind;
import com.iocextractor.domain.feature.IndicatorFeatures;
import com.iocextractor.domain.model.IndicatorType;
import com.iocextractor.domain.model.MaskMatch;
import com.iocextractor.domain.refang.RefangOutcome;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

class CsvProcessedImportRowPreparerTest {

    @Test
    void replacesDerivedFieldsThroughOrdinaryPolicyAndPreservesOperatorFields() {
        CanonicalArtifactKeyResolver keys = keys(new ArtifactIdentityDefinition(
                "masks", new CanonicalKeyDefinition(
                        "mask-row-v1", CanonicalKeyMode.COMPOSITE, List.of("mask")),
                List.of(new CanonicalKeyDefinition(
                        "mask-match-v1", CanonicalKeyMode.COMPOSITE, List.of("mask"))), 1));
        CsvArtifactDefinition definition = definition("masks", List.of(
                column("id", "id"),
                column("mask", "value", "lower-host"),
                column("url_match", "match.url"),
                column("host_match", "match.host"),
                column("score", "const"),
                column("source", "source.label")));
        CsvProcessedImportRowPreparer processed = processed(List.of(definition));
        DataframeImportRowMapper mapper = new DataframeImportRowMapper(
                (specification, value) -> value, keys, processed);

        var result = mapper.map(contract("masks", "mask-row-v1", List.of("mask-match-v1"),
                        List.of(mapping("mask", "ioc"), mapping("url_match", "match"),
                                mapping("score", "score"), mapping("source", "source"))),
                new ImportDelimitedRecord(2, Map.of(
                        "ioc", "hxxp://EVIL.example/Path", "match", "operator-value",
                        "score", "99", "source", "Feed A")));

        assertThat(result.issues()).isEmpty();
        assertThat(result.row()).hasValueSatisfying(row -> {
            var branch = row.branches().getFirst();
            assertThat(branch.cells())
                    .containsEntry("mask", ImportCell.value("http://evil.example/Path"))
                    .containsEntry("url_match", ImportCell.value("u:hAS,pEX"))
                    .containsEntry("host_match", ImportCell.nullValue())
                    .containsEntry("score", ImportCell.value("99"))
                    .containsEntry("source", ImportCell.value("Feed A"));
            assertThat(branch.recordKey()).isPresent();
            assertThat(branch.matchKeys()).extracting(key -> key.definitionId())
                    .containsExactly("mask-match-v1");
        });
    }

    @Test
    void keepsCorrelatedUrlAndIpInOneCompoundArtifactRow() {
        CanonicalArtifactKeyResolver keys = keys(new ArtifactIdentityDefinition(
                "address_blacklist", new CanonicalKeyDefinition(
                        "address-row-v2", CanonicalKeyMode.COMPOSITE,
                        List.of("forbidden_url", "forbidden_ip")), List.of(), 2));
        CsvArtifactDefinition definition = definition("address_blacklist", List.of(
                column("forbidden_url", "address.url", "lower-host"),
                column("forbidden_ip", "address.ip", "lower-host")));
        DataframeImportRowMapper mapper = new DataframeImportRowMapper(
                (specification, value) -> value, keys, processed(List.of(definition)));

        var result = mapper.map(contract("address_blacklist", "address-row-v2", List.of(),
                        List.of(mapping("forbidden_url", "url"), mapping("forbidden_ip", "ip"))),
                new ImportDelimitedRecord(7, Map.of(
                        "url", "hxxp://EVIL.example/drop", "ip", "192.0.2.44")));

        assertThat(result.issues()).isEmpty();
        assertThat(result.row()).hasValueSatisfying(row -> assertThat(row.branches().getFirst().cells())
                .containsEntry("forbidden_url", ImportCell.value("http://evil.example/drop"))
                .containsEntry("forbidden_ip", ImportCell.value("192.0.2.44")));
    }

    @Test
    void usesExplicitNameBindingAsTheProcessedSourceLabel() {
        CanonicalArtifactKeyResolver keys = keys(new ArtifactIdentityDefinition(
                "ioc_aggregate", new CanonicalKeyDefinition(
                        "ioc-aggregate-row-v1", CanonicalKeyMode.COMPOSITE,
                        List.of("ip_address", "url_match", "host_match", "hash")),
                List.of(new CanonicalKeyDefinition(
                        "ioc-aggregate-v1", CanonicalKeyMode.COMPOSITE,
                        List.of("ip_address", "url_match", "host_match", "hash"))), 1));
        CsvArtifactDefinition definition = definition("ioc_aggregate", List.of(
                column("name", "source.label"),
                column("ip_address", "address.ip"),
                column("url_match", "value", "lower-host"),
                column("host_match", "value", "lower-host"),
                column("hash", "value")));
        DataframeImportRowMapper mapper = new DataframeImportRowMapper(
                (specification, value) -> value, keys, processed(List.of(definition)));
        DataframeImportCatalogDraft.Artifact artifact = new DataframeImportCatalogDraft.Artifact(
                "ioc_aggregate", ImportArtifactRole.PRIMARY, "ioc-aggregate-row-v1",
                List.of("ioc-aggregate-v1"), null, "name", null,
                List.of(mapping("name", "feed"), mapping("url_match", "ioc")));
        CompiledDataframeImportContract contract = contract(artifact);

        var result = mapper.map(contract, new ImportDelimitedRecord(3, Map.of(
                "feed", "Threat Feed B", "ioc", "hxxp://EVIL.example/Path")));

        assertThat(result.issues()).isEmpty();
        assertThat(result.row()).hasValueSatisfying(row -> assertThat(row.branches().getFirst().cells())
                .containsEntry("name", ImportCell.value("Threat Feed B"))
                .containsEntry("url_match", ImportCell.value("http://evil.example/Path")));
    }

    @Test
    void sourceLabelTransformKeepsItsAuthorityAndMissingPresence() {
        CanonicalArtifactKeyResolver keys = keys(new ArtifactIdentityDefinition(
                "masks", new CanonicalKeyDefinition("mask-row-v1", CanonicalKeyMode.COMPOSITE,
                        List.of("mask")), List.of(), 1));
        CsvArtifactDefinition definition = definition("masks", List.of(
                column("mask", "value", "lower-host"),
                column("source", "source.label", "strip-prefix:Feed ")));
        DataframeImportCatalogDraft.Artifact artifact = new DataframeImportCatalogDraft.Artifact(
                "masks", ImportArtifactRole.PRIMARY, "mask-row-v1", List.of(), null, "source", null,
                List.of(mapping("mask", "ioc"), mapping("source", "feed")));
        CsvProcessedImportRowPreparer preparer = processed(List.of(definition));
        DataframeImportRowMapper mapper = new DataframeImportRowMapper(
                (specification, value) -> value, keys, preparer);
        CompiledDataframeImportContract contract = contract(artifact);

        var present = mapper.map(contract, new ImportDelimitedRecord(20,
                Map.of("ioc", "https://evil.example/path", "feed", "Feed Alpha")));
        var absent = mapper.map(contract, new ImportDelimitedRecord(21,
                Map.of("ioc", "https://evil.example/path")));
        var explicitNull = mapper.map(contract, new ImportDelimitedRecord(22,
                Map.of("ioc", "https://evil.example/path", "feed", "NULL")));

        assertThat(present.issues()).isEmpty();
        assertThat(present.row().orElseThrow().branches().getFirst().cells())
                .containsEntry("source", ImportCell.value("Alpha"));
        assertThat(absent.issues()).isEmpty();
        assertThat(absent.row().orElseThrow().branches().getFirst().cells())
                .containsEntry("source", ImportCell.absent());
        assertThat(explicitNull.issues()).isEmpty();
        assertThat(explicitNull.row().orElseThrow().branches().getFirst().cells())
                .containsEntry("source", ImportCell.nullValue());
        assertThat(preparer.authorizesSourceLabel(contract, "masks", "source",
                ImportCell.value("Feed Alpha"), ImportCell.value("Feed Alpha"))).isFalse();
        assertThat(preparer.authorizesSourceLabel(contract, "masks", "source",
                ImportCell.value("Feed Alpha"), ImportCell.value("Forged"))).isFalse();
    }

    @Test
    void sourceLabelTypeGateRetainsTheAdmittedValueForAnUnmatchedUrl() {
        ColumnSpec source = new ColumnSpec("source", "source.label", null,
                IndicatorType.DOMAIN, List.of("strip-prefix:Feed "));
        var result = mapWithGatedSource(source, Map.of());

        assertThat(result.issues()).isEmpty();
        assertThat(result.row().orElseThrow().branches().getFirst().cells())
                .containsEntry("source", ImportCell.value("Feed Alpha"));
    }

    @Test
    void sourceLabelConditionRetainsTheAdmittedValueWhenNoInputMatches() {
        ColumnSpec source = new ColumnSpec("source", "source.label", null, null,
                List.of("strip-prefix:Feed "), null, List.of("domain-only"));
        var result = mapWithGatedSource(source,
                Map.of("domain-only", indicator -> indicator.indicator().type() == IndicatorType.DOMAIN));

        assertThat(result.issues()).isEmpty();
        assertThat(result.row().orElseThrow().branches().getFirst().cells())
                .containsEntry("source", ImportCell.value("Feed Alpha"));
    }

    @Test
    void sourceLabelConditionAppliesTransformsWhenAnInputMatches() {
        ColumnSpec source = new ColumnSpec("source", "source.label", null, null,
                List.of("strip-prefix:Feed "), null, List.of("url-only"));
        var result = mapWithGatedSource(source,
                Map.of("url-only", indicator -> indicator.indicator().type() == IndicatorType.URL));

        assertThat(result.issues()).isEmpty();
        assertThat(result.row().orElseThrow().branches().getFirst().cells())
                .containsEntry("source", ImportCell.value("Alpha"));
    }

    @Test
    void gatedOutSourceLabelDoesNotInvokeItsTransformDuringAuthorityCheck() {
        ColumnSpec source = new ColumnSpec("source", "source.label", null,
                IndicatorType.DOMAIN, List.of("reject"));
        var result = mapWithGatedSource(source, Map.of());

        assertThat(result.issues()).isEmpty();
        assertThat(result.row().orElseThrow().branches().getFirst().cells())
                .containsEntry("source", ImportCell.value("Feed Alpha"));
    }

    private ImportRowMappingResult mapWithGatedSource(
            ColumnSpec sourceColumn, Map<String, Predicate<ClassifiedIndicator>> conditions) {
        CanonicalArtifactKeyResolver keys = keys(new ArtifactIdentityDefinition(
                "masks", new CanonicalKeyDefinition("mask-row-v1", CanonicalKeyMode.COMPOSITE,
                        List.of("mask")), List.of(), 1));
        CsvArtifactDefinition definition = new CsvArtifactDefinition("masks",
                java.util.EnumSet.allOf(IndicatorType.class), ArtifactFilter.none(),
                new ConfigurableRowMapper(List.of(column("mask", "value", "lower-host"), sourceColumn),
                        providers(), Map.of("lower-host", new LowerHostTransform(),
                                "strip-prefix", new StripPrefixTransform(),
                                "reject", (value, argument) -> {
                                    throw new MappingValueException("Gated source transform was called");
                                }), conditions),
                ArtifactIdStrategy.ASCENDING, 1);
        DataframeImportCatalogDraft.Artifact artifact = new DataframeImportCatalogDraft.Artifact(
                "masks", ImportArtifactRole.PRIMARY, "mask-row-v1", List.of(), null, "source", null,
                List.of(mapping("mask", "ioc"), mapping("source", "feed")));
        DataframeImportRowMapper mapper = new DataframeImportRowMapper(
                (specification, value) -> value, keys, processed(List.of(definition)));

        return mapper.map(contract(artifact), new ImportDelimitedRecord(23,
                Map.of("ioc", "https://evil.example/path", "feed", "Feed Alpha")));
    }

    @Test
    void rejectsAFreeTextCellThatDoesNotContainExactlyOneWholeIndicator() {
        CanonicalArtifactKeyResolver keys = keys(new ArtifactIdentityDefinition(
                "masks", List.of("mask"), false, 1));
        CsvArtifactDefinition definition = definition("masks", List.of(column("mask", "value")));
        DataframeImportRowMapper mapper = new DataframeImportRowMapper(
                (specification, value) -> value, keys, processed(List.of(definition)));

        var result = mapper.map(contract("masks", "masks-row-v1", List.of(),
                        List.of(mapping("mask", "ioc"))),
                new ImportDelimitedRecord(9, Map.of("ioc", "prefix 192.0.2.1 suffix")));

        assertThat(result.row()).isEmpty();
        assertThat(result.issues()).extracting(issue -> issue.code())
                .contains("IMPORT.PROCESSED_INPUT_INVALID");
    }

    private CsvProcessedImportRowPreparer processed(List<CsvArtifactDefinition> definitions) {
        return new CsvProcessedImportRowPreparer(
                definitions,
                text -> new RefangOutcome(text.replace("hxxp", "http"), List.of()),
                text -> {
                    int start = text.indexOf("192.0.2.1");
                    if (start >= 0 && text.contains("prefix")) {
                        return outcome(new RawIndicator("192.0.2.1", IndicatorType.IPV4, start));
                    }
                    IndicatorType type = text.matches("[0-9.]+")
                            ? IndicatorType.IPV4 : IndicatorType.URL;
                    return outcome(new RawIndicator(text.strip(), type, text.indexOf(text.strip())));
                },
                new IndicatorClassifier(indicator -> new ClassificationDecision(
                        new IndicatorFeatures(indicator.value(), indicator.value(), false,
                                indicator.value().contains("/"), false,
                                indicator.type() == IndicatorType.IPV4
                                        ? HostKind.IP : HostKind.REGISTRABLE),
                        0, List.of("test"), new MaskMatch("u:hAS,pEX", null))));
    }

    private ExtractionOutcome outcome(RawIndicator indicator) {
        return new ExtractionOutcome(List.of(indicator), List.of());
    }

    private CsvArtifactDefinition definition(String name, List<ColumnSpec> columns) {
        return new CsvArtifactDefinition(name,
                java.util.EnumSet.allOf(IndicatorType.class), ArtifactFilter.none(),
                new ConfigurableRowMapper(columns, providers(), Map.of(
                        "lower-host", new LowerHostTransform(), "strip-prefix", new StripPrefixTransform())),
                ArtifactIdStrategy.ASCENDING, 1);
    }

    private Map<String, ValueProvider> providers() {
        Map<String, ValueProvider> providers = new HashMap<>();
        providers.put("id", new IdValueProvider());
        providers.put("value", new IndicatorValueProvider());
        providers.put("match.url", new MatchUrlValueProvider());
        providers.put("match.host", new MatchHostValueProvider());
        providers.put("source.label", new SourceLabelValueProvider());
        providers.put("address.url", new AddressUrlValueProvider());
        providers.put("address.ip", new AddressIpValueProvider());
        return providers;
    }

    private CompiledDataframeImportContract contract(String artifact,
                                                       String recordKey,
                                                       List<String> matchKeys,
                                                       List<DataframeImportCatalogDraft.Column> mappings) {
        List<String> required = mappings.stream().map(DataframeImportCatalogDraft.Column::source).toList();
        var definition = new DataframeImportCatalogDraft.Contract(
                artifact + "-processed-v1", 1, "UTF-8",
                new DataframeImportCatalogDraft.Dialect(
                        ";", "\"", ImportRecordSeparator.CRLF_OR_LF, true, List.of("NULL")),
                new DataframeImportCatalogDraft.Recognition(required, List.of(), List.of(), Map.of()),
                ImportProcessingMode.PROCESSED, ImportRoutingPolicy.TARGET_ONLY,
                ImportRowFailurePolicy.ACCEPT_VALID, ImportDuplicatePolicy.COALESCE, true,
                ImportFormulaPolicy.REJECT, ImportMergePolicy.AUTHORITATIVE,
                List.of(new DataframeImportCatalogDraft.Artifact(
                        artifact, ImportArtifactRole.PRIMARY, recordKey, matchKeys, null, mappings)), null);
        return new CompiledDataframeImportContract(
                new ImportContractId(definition.id()), 1, definition,
                new DelimitedDialect(';', '"', ImportRecordSeparator.CRLF_OR_LF, true, List.of("NULL")),
                new ImportContractFingerprint("c".repeat(64)));
    }

    private CompiledDataframeImportContract contract(DataframeImportCatalogDraft.Artifact artifact) {
        List<String> required = artifact.columns().stream()
                .map(DataframeImportCatalogDraft.Column::source).toList();
        var definition = new DataframeImportCatalogDraft.Contract(
                artifact.name() + "-processed-v1", 1, "UTF-8",
                new DataframeImportCatalogDraft.Dialect(
                        ";", "\"", ImportRecordSeparator.CRLF_OR_LF, true, List.of("NULL")),
                new DataframeImportCatalogDraft.Recognition(required, List.of(), List.of(), Map.of()),
                ImportProcessingMode.PROCESSED, ImportRoutingPolicy.TARGET_ONLY,
                ImportRowFailurePolicy.ACCEPT_VALID, ImportDuplicatePolicy.COALESCE, true,
                ImportFormulaPolicy.REJECT, ImportMergePolicy.AUTHORITATIVE,
                List.of(artifact), null);
        return new CompiledDataframeImportContract(
                new ImportContractId(definition.id()), 1, definition,
                new DelimitedDialect(';', '"', ImportRecordSeparator.CRLF_OR_LF, true, List.of("NULL")),
                new ImportContractFingerprint("c".repeat(64)));
    }

    private DataframeImportCatalogDraft.Column mapping(String target, String source) {
        return new DataframeImportCatalogDraft.Column(target, source, List.of(), null);
    }

    private ColumnSpec column(String name, String from, String... transforms) {
        return new ColumnSpec(name, from, null, null, List.of(transforms));
    }

    private CanonicalArtifactKeyResolver keys(ArtifactIdentityDefinition definition) {
        return new CanonicalArtifactKeyResolver(List.of(definition));
    }
}
