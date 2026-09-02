package com.iocextractor.application.dataframeimport.mapping;

import com.iocextractor.application.artifact.ArtifactIdentityDefinition;
import com.iocextractor.application.artifact.CanonicalArtifactKeyResolver;
import com.iocextractor.application.artifact.CanonicalKeyDefinition;
import com.iocextractor.application.artifact.CanonicalKeyMode;
import com.iocextractor.application.dataframeimport.contract.CompiledDataframeImportContract;
import com.iocextractor.application.dataframeimport.contract.DataframeImportCatalogDraft;
import com.iocextractor.application.dataframeimport.model.DelimitedDialect;
import com.iocextractor.application.dataframeimport.model.ImportArtifactRole;
import com.iocextractor.application.dataframeimport.model.ImportCell;
import com.iocextractor.application.dataframeimport.model.ImportContractFingerprint;
import com.iocextractor.application.dataframeimport.model.ImportContractId;
import com.iocextractor.application.dataframeimport.model.ImportDuplicatePolicy;
import com.iocextractor.application.dataframeimport.model.ImportExistingSlotPolicy;
import com.iocextractor.application.dataframeimport.model.ImportFormulaPolicy;
import com.iocextractor.application.dataframeimport.model.ImportLogicalRow;
import com.iocextractor.application.dataframeimport.model.ImportMergePolicy;
import com.iocextractor.application.dataframeimport.model.ImportProcessingMode;
import com.iocextractor.application.dataframeimport.model.ImportRecordSeparator;
import com.iocextractor.application.dataframeimport.model.ImportRoutingPolicy;
import com.iocextractor.application.dataframeimport.model.ImportRowFailurePolicy;
import com.iocextractor.application.dataframeimport.model.ImportRowIssue;
import com.iocextractor.application.dataframeimport.model.ImportDelimitedRecord;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DataframeImportRowMapperTest {

    private final CanonicalArtifactKeyResolver keys = new CanonicalArtifactKeyResolver(List.of(
            new ArtifactIdentityDefinition(
                    "ip_list",
                    new CanonicalKeyDefinition("ip-row-v1", CanonicalKeyMode.COMPOSITE, List.of("ip")),
                    List.of(new CanonicalKeyDefinition(
                            "ip-match-v1", CanonicalKeyMode.COMPOSITE, List.of("ip"))), 1),
            new ArtifactIdentityDefinition(
                    "hashes",
                    new CanonicalKeyDefinition(
                            "hash-row-v1", CanonicalKeyMode.FIRST_NON_EMPTY, List.of("hash_md5")),
                    List.of(), 1)));

    @Test
    void preserves_tri_state_resolves_keys_and_fans_out_one_logical_row() {
        DataframeImportRowMapper mapper = new DataframeImportRowMapper(
                (specification, value) -> "trim".equals(specification) ? value.trim() : value,
                keys);

        ImportRowMappingResult result = mapper.map(contract(ImportFormulaPolicy.REJECT),
                new ImportDelimitedRecord(7, Map.of(
                        "address", " 192.0.2.1 ",
                        "score", " NULL ",
                        "md5", "A".repeat(32),
                        "external_id", "17")));

        assertThat(result.issues()).isEmpty();
        assertThat(result.row()).hasValueSatisfying(row -> {
            assertThat(row.sourceRowNumber()).isEqualTo(7);
            assertThat(row.branches()).hasSize(2);
            assertThat(row.branches().get(0).cells())
                    .containsEntry("ip", ImportCell.value("192.0.2.1"))
                    .containsEntry("score", ImportCell.nullValue())
                    .containsEntry("description", ImportCell.absent());
            assertThat(row.branches().get(0).requestedSlot()).hasValue(17);
            assertThat(row.branches().get(0).recordKey()).isPresent();
            assertThat(row.branches().get(0).matchKeys())
                    .extracting(key -> key.definitionId())
                    .containsExactly("ip-match-v1");
            assertThat(row.branches().get(1).artifactName()).isEqualTo("hashes");
            assertThat(row.branches().get(1).requestedSlot()).isEmpty();
        });
    }

    @Test
    void rejects_every_branch_when_one_related_branch_contains_formula_dangerous_text() {
        DataframeImportRowMapper mapper = new DataframeImportRowMapper((specification, value) -> value, keys);

        ImportRowMappingResult result = mapper.map(contract(ImportFormulaPolicy.REJECT),
                new ImportDelimitedRecord(8, Map.of(
                        "address", "192.0.2.1", "score", "10",
                        "md5", "=cmd", "external_id", "1")));

        assertThat(result.row()).isEmpty();
        assertThat(result.issues()).extracting(issue -> issue.code())
                .contains("IMPORT.FORMULA_REJECTED", "IMPORT.RECORD_KEY_MISSING");
    }

    @Test
    void converts_transform_rejection_and_invalid_requested_slot_to_safe_row_issues() {
        DataframeImportRowMapper mapper = new DataframeImportRowMapper((specification, value) -> {
            throw new ImportValueMappingException(
                    "raw value intentionally omitted", new IllegalArgumentException("invalid"));
        }, keys);

        ImportRowMappingResult result = mapper.map(contract(ImportFormulaPolicy.REJECT),
                new ImportDelimitedRecord(9, Map.of(
                        "address", "bad", "score", "10", "md5", "B".repeat(32),
                        "external_id", "not-a-number")));

        assertThat(result.row()).isEmpty();
        assertThat(result.issues()).extracting(issue -> issue.code())
                .contains("IMPORT.TRANSFORM_FAILED", "IMPORT.REQUESTED_SLOT_INVALID")
                .allMatch(code -> code.startsWith("IMPORT."));
    }

    @Test
    void fails_closed_when_processed_row_preparation_is_not_connected() {
        DataframeImportRowMapper mapper = new DataframeImportRowMapper((specification, value) -> value, keys);

        assertThatThrownBy(() -> mapper.map(contract(ImportFormulaPolicy.REJECT, ImportProcessingMode.PROCESSED),
                new ImportDelimitedRecord(10, Map.of(
                        "address", "192.0.2.1", "score", "10",
                        "md5", "C".repeat(32), "external_id", "2"))))
                .isInstanceOfSatisfying(ImportRowMappingException.class,
                        failure -> assertThat(failure.reason())
                                .isEqualTo(ImportRowMappingException.Reason.PROCESSED_MODE_UNAVAILABLE))
                .hasMessage("Processed import requires the dedicated preparation strategy");
    }

    @Test
    void appliesColumnArtifactAndContractMergePoliciesInPrecedenceOrder() {
        CompiledDataframeImportContract base = contract(ImportFormulaPolicy.REJECT);
        DataframeImportCatalogDraft.Contract definition = base.definition();
        DataframeImportCatalogDraft.Artifact primary = definition.artifacts().getFirst();
        DataframeImportCatalogDraft.Artifact policyAwarePrimary = new DataframeImportCatalogDraft.Artifact(
                primary.name(), primary.role(), primary.recordKey(), primary.matchKeys(),
                ImportMergePolicy.FILL_MISSING,
                List.of(
                        new DataframeImportCatalogDraft.Column(
                                "ip", "address", List.of("trim"), ImportMergePolicy.KEEP_EXISTING),
                        new DataframeImportCatalogDraft.Column(
                                "score", "score", List.of("trim"), null),
                        new DataframeImportCatalogDraft.Column(
                                "description", "note", List.of(), null)));
        CompiledDataframeImportContract contract = compiled(copyWith(
                definition,
                List.of(policyAwarePrimary, definition.artifacts().get(1)),
                definition.requestedSlot()));

        ImportRowMappingResult result = new DataframeImportRowMapper(
                (specification, value) -> value.trim(), keys).map(
                        contract,
                        new ImportDelimitedRecord(11, Map.of(
                                "address", "192.0.2.11", "score", "11",
                                "md5", "D".repeat(32), "external_id", "11")));

        assertThat(result.row()).hasValueSatisfying(row -> {
            assertThat(row.branches().getFirst().mergePolicies())
                    .containsEntry("ip", ImportMergePolicy.KEEP_EXISTING)
                    .containsEntry("score", ImportMergePolicy.FILL_MISSING)
                    .containsEntry("description", ImportMergePolicy.FILL_MISSING);
            assertThat(row.branches().get(1).mergePolicies())
                    .containsEntry("hash_md5", ImportMergePolicy.AUTHORITATIVE);
        });
    }

    @Test
    void distinguishesEmptyNullAndMissingRequestedSlotValues() {
        DataframeImportRowMapper mapper = new DataframeImportRowMapper(
                (specification, value) -> value.trim(), keys);

        ImportRowMappingResult emptyCell = mapper.map(contract(ImportFormulaPolicy.REJECT),
                record(12, "192.0.2.12", "", "E".repeat(32), ""));
        ImportRowMappingResult transformedEmptyCell = mapper.map(contract(ImportFormulaPolicy.REJECT),
                record(13, "192.0.2.13", "   ", "F".repeat(32), "NULL"));
        ImportRowMappingResult missingSlot = mapper.map(contract(ImportFormulaPolicy.REJECT),
                new ImportDelimitedRecord(14, Map.of(
                        "address", "192.0.2.14", "score", "14", "md5", "1".repeat(32))));
        ImportRowMappingResult nonPositiveSlot = mapper.map(contract(ImportFormulaPolicy.REJECT),
                record(15, "192.0.2.15", "15", "2".repeat(32), "0"));

        assertThat(emptyCell.row()).hasValueSatisfying(row -> {
            assertThat(row.branches().getFirst().cells().get("score")).isEqualTo(ImportCell.nullValue());
            assertThat(row.branches().getFirst().requestedSlot()).isEmpty();
        });
        assertThat(transformedEmptyCell.row()).hasValueSatisfying(row -> {
            assertThat(row.branches().getFirst().cells().get("score")).isEqualTo(ImportCell.nullValue());
            assertThat(row.branches().getFirst().requestedSlot()).isEmpty();
        });
        assertThat(missingSlot.row()).hasValueSatisfying(row ->
                assertThat(row.branches().getFirst().requestedSlot()).isEmpty());
        assertThat(nonPositiveSlot.issues()).extracting(ImportRowIssue::code)
                .containsExactly("IMPORT.REQUESTED_SLOT_INVALID");

        DataframeImportCatalogDraft.Contract definition = contract(ImportFormulaPolicy.REJECT).definition();
        ImportRowMappingResult unconfiguredSlot = mapper.map(
                compiled(copyWith(definition, definition.artifacts(), null)),
                record(16, "192.0.2.16", "16", "3".repeat(32), "99"));
        assertThat(unconfiguredSlot.row()).hasValueSatisfying(row ->
                assertThat(row.branches().getFirst().requestedSlot()).isEmpty());
    }

    @Test
    void rejectsEverySpreadsheetFormulaPrefixButAllowsConfiguredMachineOnlyValues() {
        DataframeImportRowMapper mapper = new DataframeImportRowMapper(
                (specification, value) -> value.trim(), keys);
        for (String value : List.of("=cmd", "+cmd", "-cmd", "@cmd")) {
            ImportRowMappingResult rejected = mapper.map(contract(ImportFormulaPolicy.REJECT),
                    record(17, "192.0.2.17", "17", value, "17"));
            assertThat(rejected.issues()).extracting(ImportRowIssue::code)
                    .as(value)
                    .contains("IMPORT.FORMULA_REJECTED");
        }

        ImportRowMappingResult whitespace = mapper.map(contract(ImportFormulaPolicy.REJECT),
                record(18, "192.0.2.18", "18", "   ", "18"));
        ImportRowMappingResult allowed = mapper.map(contract(ImportFormulaPolicy.MACHINE_ONLY_PRESERVE),
                record(19, "192.0.2.19", "19", "=machine-value", "19"));

        assertThat(whitespace.issues()).extracting(ImportRowIssue::code)
                .containsExactly("IMPORT.RECORD_KEY_MISSING");
        assertThat(allowed.issues()).isEmpty();
        assertThat(allowed.row()).hasValueSatisfying(row ->
                assertThat(row.branches().get(1).cells().get("hash_md5"))
                        .isEqualTo(ImportCell.value("=machine-value")));
    }

    @Test
    void delegatesACompleteLogicalRowToProcessedPreparation() {
        AtomicReference<ImportLogicalRow> prepared = new AtomicReference<>();
        DataframeImportRowMapper mapper = new DataframeImportRowMapper(
                (specification, value) -> value.trim(),
                keys,
                (contract, record, mapped) -> {
                    prepared.set(mapped);
                    return ImportRowMappingResult.accepted(mapped);
                });

        ImportRowMappingResult result = mapper.map(
                contract(ImportFormulaPolicy.REJECT, ImportProcessingMode.PROCESSED),
                record(20, "192.0.2.20", "20", "4".repeat(32), "20"));

        assertThat(result.issues()).isEmpty();
        assertThat(prepared.get()).isEqualTo(result.row().orElseThrow());
        assertThat(prepared.get().branches()).hasSize(2);
    }

    private CompiledDataframeImportContract contract(ImportFormulaPolicy formulaPolicy) {
        return contract(formulaPolicy, ImportProcessingMode.AS_IS);
    }

    private CompiledDataframeImportContract contract(ImportFormulaPolicy formulaPolicy,
                                                       ImportProcessingMode mode) {
        DataframeImportCatalogDraft.Contract definition = new DataframeImportCatalogDraft.Contract(
                "compound-v1", 1, "UTF-8",
                new DataframeImportCatalogDraft.Dialect(
                        ";", "\"", ImportRecordSeparator.CRLF_OR_LF, true, List.of("NULL")),
                new DataframeImportCatalogDraft.Recognition(
                        List.of("address", "score", "md5", "external_id"),
                        List.of("note"), List.of(), Map.of()),
                mode, ImportRoutingPolicy.RELATED_ARTIFACTS,
                ImportRowFailurePolicy.ACCEPT_VALID, ImportDuplicatePolicy.COALESCE, true,
                formulaPolicy, ImportMergePolicy.AUTHORITATIVE,
                List.of(
                        new DataframeImportCatalogDraft.Artifact(
                                "ip_list", ImportArtifactRole.PRIMARY, "ip-row-v1",
                                List.of("ip-match-v1"), null,
                                List.of(
                                        new DataframeImportCatalogDraft.Column(
                                                "ip", "address", List.of("trim"), null),
                                        new DataframeImportCatalogDraft.Column(
                                                "score", "score", List.of("trim"), null),
                                        new DataframeImportCatalogDraft.Column(
                                                "description", "note", List.of(), null))),
                        new DataframeImportCatalogDraft.Artifact(
                                "hashes", ImportArtifactRole.RELATED, "hash-row-v1", List.of(), null,
                                List.of(new DataframeImportCatalogDraft.Column(
                                        "hash_md5", "md5", List.of(), null)))),
                new DataframeImportCatalogDraft.RequestedSlot(
                        "external_id", "reputation-lists", ImportExistingSlotPolicy.PRESERVE_EXISTING));
        return compiled(definition);
    }

    private CompiledDataframeImportContract compiled(DataframeImportCatalogDraft.Contract definition) {
        return new CompiledDataframeImportContract(
                new ImportContractId(definition.id()), definition.version(), definition,
                new DelimitedDialect(';', '"', ImportRecordSeparator.CRLF_OR_LF, true, List.of("NULL")),
                new ImportContractFingerprint("c".repeat(64)));
    }

    private DataframeImportCatalogDraft.Contract copyWith(
            DataframeImportCatalogDraft.Contract definition,
            List<DataframeImportCatalogDraft.Artifact> artifacts,
            DataframeImportCatalogDraft.RequestedSlot requestedSlot) {
        return new DataframeImportCatalogDraft.Contract(
                definition.id(), definition.version(), definition.charset(), definition.dialect(),
                definition.recognition(), definition.mode(), definition.routing(),
                definition.rowFailurePolicy(), definition.duplicatePolicy(), definition.renewUnchanged(),
                definition.formulaPolicy(), definition.mergeDefault(), artifacts, requestedSlot);
    }

    private ImportDelimitedRecord record(
            long rowNumber,
            String address,
            String score,
            String md5,
            String externalId) {
        return new ImportDelimitedRecord(rowNumber, Map.of(
                "address", address,
                "score", score,
                "md5", md5,
                "external_id", externalId));
    }
}
