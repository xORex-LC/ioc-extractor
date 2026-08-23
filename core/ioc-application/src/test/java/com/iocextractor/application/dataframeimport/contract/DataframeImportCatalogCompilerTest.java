package com.iocextractor.application.dataframeimport.contract;

import com.iocextractor.application.dataframeimport.model.ImportArtifactRole;
import com.iocextractor.application.dataframeimport.model.ImportDuplicatePolicy;
import com.iocextractor.application.dataframeimport.model.ImportFormulaPolicy;
import com.iocextractor.application.dataframeimport.model.ImportMergePolicy;
import com.iocextractor.application.dataframeimport.model.ImportProcessingMode;
import com.iocextractor.application.dataframeimport.model.ImportRecordSeparator;
import com.iocextractor.application.dataframeimport.model.ImportRoutingPolicy;
import com.iocextractor.application.dataframeimport.model.ImportRowFailurePolicy;
import com.iocextractor.application.dataframeimport.model.ImportSourceTransport;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DataframeImportCatalogCompilerTest {

    private final DataframeImportCatalogCompiler compiler = new DataframeImportCatalogCompiler();

    @Test
    void compilesAResolvedImmutableCatalogWithSha256Fingerprints() {
        DataframeImportCatalogCompilation compilation = compiler.compile(validDraft(List.of("ip", "score")), environment());

        assertThat(compilation.valid()).isTrue();
        DataframeImportCatalog catalog = compilation.catalogOrThrow();
        assertThat(catalog.fingerprint().value()).matches("[0-9a-f]{64}");
        assertThat(catalog.contracts().values()).singleElement()
                .satisfies(contract -> {
                    assertThat(contract.fingerprint().value()).matches("[0-9a-f]{64}");
                    assertThat(contract.dialect().delimiter()).isEqualTo(';');
                    assertThat(contract.dialect().quote()).isEqualTo('"');
                    assertThat(contract.dialect().headerRequired()).isTrue();
                    assertThat(contract.dialect().nullLiterals()).containsExactly("NULL");
                });
        assertThatThrownBy(() -> catalog.sources().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void draftSnapshotsCollectionsWithoutMaskingNullsNeededByCollectAllValidation() {
        var sources = new ArrayList<DataframeImportCatalogDraft.Source>(
                Arrays.asList((DataframeImportCatalogDraft.Source) null));
        var aliases = new LinkedHashMap<String, String>();
        aliases.put("external", null);
        var requiredColumns = new ArrayList<>(Arrays.asList("ip", null));
        var recognition = new DataframeImportCatalogDraft.Recognition(
                requiredColumns, List.of(), List.of(), aliases);
        var draft = new DataframeImportCatalogDraft(true, sources, List.of(), List.of());

        sources.clear();
        aliases.clear();
        requiredColumns.clear();

        assertThat(draft.sources()).containsExactly((DataframeImportCatalogDraft.Source) null);
        assertThat(recognition.requiredColumns()).containsExactly("ip", null);
        assertThat(recognition.aliases()).containsEntry("external", null);
        assertThatThrownBy(draft.sources()::clear).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(recognition.requiredColumns()::clear).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(recognition.aliases()::clear).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void fingerprintIsStableForSetOrderingButSensitiveToTransformOrdering() {
        String first = compiler.compile(validDraft(List.of("ip", "score")), environment())
                .catalogOrThrow().fingerprint().value();
        String reorderedHeaders = compiler.compile(validDraft(List.of("score", "ip")), environment())
                .catalogOrThrow().fingerprint().value();
        String reorderedTransforms = compiler.compile(draftWithTransforms(List.of("upper", "lower")), environment())
                .catalogOrThrow().fingerprint().value();

        assertThat(reorderedHeaders).isEqualTo(first);
        assertThat(reorderedTransforms).isNotEqualTo(first);
    }

    @Test
    void reportsEveryMissingEnabledCatalogSection() {
        DataframeImportCatalogCompilation compilation = compiler.compile(
                new DataframeImportCatalogDraft(true, List.of(), List.of(), List.of()), environment());

        assertThat(compilation.valid()).isFalse();
        assertThat(compilation.violations()).extracting(ImportContractViolation::path)
                .contains("sources", "authority-profiles", "contracts");
    }

    @Test
    void reportsNullRecognitionAliasWithoutAbortingCollectAllValidation() {
        Map<String, String> aliases = new LinkedHashMap<>();
        aliases.put(null, "ip");
        aliases.put("external", null);

        DataframeImportCatalogCompilation compilation = compiler.compile(
                draft(List.of("ip", "score"), List.of("lower", "upper"), aliases), environment());

        assertThat(compilation.valid()).isFalse();
        assertThat(compilation.violations()).extracting(ImportContractViolation::path)
                .containsOnly("contracts[0].recognition.aliases")
                .hasSize(2);
    }

    @Test
    void rejectsAContractThatExceedsItsSourceAuthorityCeiling() {
        DataframeImportCatalogDraft valid = validDraft(List.of("ip", "score"));
        DataframeImportCatalogDraft restricted = new DataframeImportCatalogDraft(true, valid.sources(),
                List.of(new DataframeImportCatalogDraft.AuthorityProfile(
                        "standard", List.of("ip_list"), ImportMergePolicy.FILL_MISSING, false, false)),
                valid.contracts());

        DataframeImportCatalogCompilation compilation = compiler.compile(restricted, environment());

        assertThat(compilation.violations()).extracting(ImportContractViolation::message)
                .contains("contract default merge policy exceeds source authority");
    }

    @Test
    void disabledEmptyCatalogIsAValidNoop() {
        DataframeImportCatalogCompilation compilation = compiler.compile(
                new DataframeImportCatalogDraft(false, List.of(), List.of(), List.of()), environment());

        assertThat(compilation.valid()).isTrue();
        assertThat(compilation.catalogOrThrow().enabled()).isFalse();
    }

    private DataframeImportCatalogDraft validDraft(List<String> requiredHeaders) {
        return draft(requiredHeaders, List.of("lower", "upper"));
    }

    private DataframeImportCatalogDraft draftWithTransforms(List<String> transforms) {
        return draft(List.of("ip", "score"), transforms);
    }

    private DataframeImportCatalogDraft draft(List<String> requiredHeaders, List<String> transforms) {
        return draft(requiredHeaders, transforms, Map.of());
    }

    private DataframeImportCatalogDraft draft(List<String> requiredHeaders,
                                              List<String> transforms,
                                              Map<String, String> aliases) {
        DataframeImportCatalogDraft.Contract contract = new DataframeImportCatalogDraft.Contract(
                "ip-list-v1", 1, "UTF-8",
                new DataframeImportCatalogDraft.Dialect(
                        ";", "\"", ImportRecordSeparator.CRLF_OR_LF, true, List.of("NULL")),
                new DataframeImportCatalogDraft.Recognition(requiredHeaders, List.of(), List.of(), aliases),
                ImportProcessingMode.AS_IS, ImportRoutingPolicy.TARGET_ONLY,
                ImportRowFailurePolicy.ACCEPT_VALID, ImportDuplicatePolicy.COALESCE, true,
                ImportFormulaPolicy.REJECT, ImportMergePolicy.AUTHORITATIVE,
                List.of(new DataframeImportCatalogDraft.Artifact(
                        "ip_list", ImportArtifactRole.PRIMARY, "ip-row-v1", List.of("ip-v1"), null,
                        List.of(
                                new DataframeImportCatalogDraft.Column("ip", "ip", transforms, null),
                                new DataframeImportCatalogDraft.Column("score", "score", List.of(), null)))),
                null);
        return new DataframeImportCatalogDraft(true,
                List.of(new DataframeImportCatalogDraft.Source(
                        "local", ImportSourceTransport.LOCAL, "./var/import", null,
                        List.of("ip-list-v1"), "standard")),
                List.of(new DataframeImportCatalogDraft.AuthorityProfile(
                        "standard", List.of("ip_list"), ImportMergePolicy.AUTHORITATIVE, false, false)),
                List.of(contract));
    }

    private DataframeImportCatalogEnvironment environment() {
        return new DataframeImportCatalogEnvironment(
                Map.of("ip_list", new DataframeImportCatalogEnvironment.ArtifactSchema(
                        Set.of("ip", "score"), "ip-row-v1", Set.of("ip-v1"),
                        Set.of("reputation-lists"), true)),
                Set.of("lower", "upper"),
                Set.of("upstream"));
    }
}
