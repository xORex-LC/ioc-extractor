package com.iocextractor.adapter.in.csv;

import com.iocextractor.adapter.out.sink.csv.AddressIpValueProvider;
import com.iocextractor.adapter.out.sink.csv.AddressUrlValueProvider;
import com.iocextractor.adapter.out.sink.csv.ArtifactFilter;
import com.iocextractor.adapter.out.sink.csv.ColumnSpec;
import com.iocextractor.adapter.out.sink.csv.ConfigurableRowMapper;
import com.iocextractor.adapter.out.sink.csv.CsvArtifactDefinition;
import com.iocextractor.adapter.out.sink.csv.IdValueProvider;
import com.iocextractor.adapter.out.sink.csv.IndicatorValueProvider;
import com.iocextractor.adapter.out.sink.csv.LowerHostTransform;
import com.iocextractor.adapter.out.sink.csv.MatchHostValueProvider;
import com.iocextractor.adapter.out.sink.csv.MatchUrlValueProvider;
import com.iocextractor.adapter.out.sink.csv.SourceLabelValueProvider;
import com.iocextractor.adapter.out.sink.csv.ValueProvider;
import com.iocextractor.application.artifact.ArtifactIdStrategy;
import com.iocextractor.application.artifact.ArtifactIdentityDefinition;
import com.iocextractor.application.artifact.CanonicalArtifactKeyResolver;
import com.iocextractor.application.artifact.CanonicalKeyDefinition;
import com.iocextractor.application.artifact.CanonicalKeyMode;
import com.iocextractor.application.classification.IndicatorClassifier;
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
        CsvProcessedImportRowPreparer processed = processed(List.of(definition), keys);
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
                (specification, value) -> value, keys, processed(List.of(definition), keys));

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
    void rejectsAFreeTextCellThatDoesNotContainExactlyOneWholeIndicator() {
        CanonicalArtifactKeyResolver keys = keys(new ArtifactIdentityDefinition(
                "masks", List.of("mask"), false, 1));
        CsvArtifactDefinition definition = definition("masks", List.of(column("mask", "value")));
        DataframeImportRowMapper mapper = new DataframeImportRowMapper(
                (specification, value) -> value, keys, processed(List.of(definition), keys));

        var result = mapper.map(contract("masks", "masks-row-v1", List.of(),
                        List.of(mapping("mask", "ioc"))),
                new ImportDelimitedRecord(9, Map.of("ioc", "prefix 192.0.2.1 suffix")));

        assertThat(result.row()).isEmpty();
        assertThat(result.issues()).extracting(issue -> issue.code())
                .contains("IMPORT.PROCESSED_INPUT_INVALID");
    }

    private CsvProcessedImportRowPreparer processed(List<CsvArtifactDefinition> definitions,
                                                     CanonicalArtifactKeyResolver keys) {
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
                        0, List.of("test"), new MaskMatch("u:hAS,pEX", null))),
                keys);
    }

    private ExtractionOutcome outcome(RawIndicator indicator) {
        return new ExtractionOutcome(List.of(indicator), List.of());
    }

    private CsvArtifactDefinition definition(String name, List<ColumnSpec> columns) {
        return new CsvArtifactDefinition(name,
                java.util.EnumSet.allOf(IndicatorType.class), ArtifactFilter.none(),
                new ConfigurableRowMapper(columns, providers(), Map.of("lower-host", new LowerHostTransform())),
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
