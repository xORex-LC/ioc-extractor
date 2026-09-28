package com.iocextractor.application.dataframeimport.mapping;


import com.iocextractor.application.artifact.ArtifactIdentityDefinition;
import com.iocextractor.application.artifact.CanonicalArtifactKeyResolver;
import com.iocextractor.application.artifact.CanonicalKeyDefinition;
import com.iocextractor.application.artifact.CanonicalKeyMode;
import com.iocextractor.application.dataframeimport.contract.CompiledDataframeImportContract;
import com.iocextractor.application.dataframeimport.contract.DataframeImportCatalogDraft;
import com.iocextractor.application.dataframeimport.model.DelimitedDialect;
import com.iocextractor.application.dataframeimport.model.ImportArtifactBranch;
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
import com.iocextractor.application.dataframeimport.model.ImportRowWarning;
import com.iocextractor.application.dataframeimport.model.ImportDelimitedRecord;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;

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
        DataframeImportRowMapper caseChangingMapper = new DataframeImportRowMapper(
                (specification, value) -> value.toLowerCase(), keys);
        ImportRowMappingResult nullBeforeTransform = caseChangingMapper.map(
                contract(ImportFormulaPolicy.REJECT),
                record(14, "192.0.2.14", "NULL", "1".repeat(32), "14"));
        ImportRowMappingResult missingSlot = mapper.map(contract(ImportFormulaPolicy.REJECT),
                new ImportDelimitedRecord(15, Map.of(
                        "address", "192.0.2.15", "score", "15", "md5", "2".repeat(32))));
        ImportRowMappingResult nonPositiveSlot = mapper.map(contract(ImportFormulaPolicy.REJECT),
                record(16, "192.0.2.16", "16", "3".repeat(32), "0"));

        assertThat(emptyCell.row()).hasValueSatisfying(row -> {
            assertThat(row.branches().getFirst().cells().get("score")).isEqualTo(ImportCell.nullValue());
            assertThat(row.branches().getFirst().requestedSlot()).isEmpty();
        });
        assertThat(transformedEmptyCell.row()).hasValueSatisfying(row -> {
            assertThat(row.branches().getFirst().cells().get("score")).isEqualTo(ImportCell.nullValue());
            assertThat(row.branches().getFirst().requestedSlot()).isEmpty();
        });
        assertThat(nullBeforeTransform.issues()).isEmpty();
        assertThat(nullBeforeTransform.row()).hasValueSatisfying(row ->
                assertThat(row.branches().getFirst().cells().get("score"))
                        .isEqualTo(ImportCell.nullValue()));
        assertThat(missingSlot.row()).hasValueSatisfying(row ->
                assertThat(row.branches().getFirst().requestedSlot()).isEmpty());
        assertThat(nonPositiveSlot.issues()).extracting(ImportRowIssue::code)
                .containsExactly("IMPORT.REQUESTED_SLOT_INVALID");

        DataframeImportCatalogDraft.Contract definition = contract(ImportFormulaPolicy.REJECT).definition();
        ImportRowMappingResult unconfiguredSlot = mapper.map(
                compiled(copyWith(definition, definition.artifacts(), null)),
                record(17, "192.0.2.17", "17", "4".repeat(32), "99"));
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
        assertThat(prepared.get().branches()).allSatisfy(branch -> {
            assertThat(branch.recordKey()).isEmpty();
            assertThat(branch.matchKeys()).isEmpty();
        });
        assertThat(result.row().orElseThrow().branches()).allSatisfy(branch ->
                assertThat(branch.recordKey()).isPresent());
        assertThat(prepared.get().branches()).hasSize(2);
    }

    @Test
    void resolvesProcessedIdentityFromFinalFieldsAndKeepsAcceptedWarning() {
        DataframeImportRowMapper mapper = new DataframeImportRowMapper(
                (specification, value) -> value, keys,
                (contract, record, admitted) -> {
                    var branches = new java.util.ArrayList<>(admitted.branches());
                    var primary = branches.getFirst();
                    var cells = new java.util.LinkedHashMap<>(primary.cells());
                    cells.put("ip", ImportCell.value("192.0.2.99"));
                    branches.set(0, new com.iocextractor.application.dataframeimport.model.ImportArtifactBranch(
                            primary.artifactName(), primary.role(), cells, primary.mergePolicies(),
                            primary.requestedSlot(), java.util.Optional.empty(), List.of()));
                    return ImportRowMappingResult.accepted(
                            new ImportLogicalRow(record.sourceRowNumber(), branches),
                            List.of(new ImportRowWarning(record.sourceRowNumber(), "ip_list", "IMPORT.VIEW_FALLBACK")));
                });

        ImportRowMappingResult result = mapper.map(
                contract(ImportFormulaPolicy.REJECT, ImportProcessingMode.PROCESSED),
                record(23, "bad-input", "23", "7".repeat(32), "23"));

        assertThat(result.issues()).isEmpty();
        assertThat(result.warnings()).extracting(ImportRowWarning::code)
                .containsExactly("IMPORT.VIEW_FALLBACK");
        assertThat(result.row()).hasValueSatisfying(row -> {
            assertThat(row.branches().getFirst().cells().get("ip"))
                    .isEqualTo(ImportCell.value("192.0.2.99"));
            assertThat(row.branches().getFirst().recordKey()).isPresent();
        });
    }

    @Test
    void refusesRemovalOfAnAdmittedProcessedCell() {
        DataframeImportRowMapper mapper = new DataframeImportRowMapper(
                (specification, value) -> value, keys,
                (contract, record, admitted) -> {
                    var branches = new java.util.ArrayList<>(admitted.branches());
                    var primary = branches.getFirst();
                    var cells = new java.util.LinkedHashMap<>(primary.cells());
                    var policies = new java.util.LinkedHashMap<>(primary.mergePolicies());
                    cells.remove("score");
                    policies.remove("score");
                    branches.set(0, new com.iocextractor.application.dataframeimport.model.ImportArtifactBranch(
                            primary.artifactName(), primary.role(), cells, policies,
                            primary.requestedSlot(), java.util.Optional.empty(), List.of()));
                    return ImportRowMappingResult.accepted(
                            new ImportLogicalRow(record.sourceRowNumber(), branches));
                });

        assertThatThrownBy(() -> mapper.map(
                contract(ImportFormulaPolicy.REJECT, ImportProcessingMode.PROCESSED),
                record(24, "192.0.2.24", "24", "8".repeat(32), "24")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("admitted cells");
    }

    @Test
    void processedFinalizationRefusesChangesToRowAndBranchAuthority() {
        assertProcessedMutationRejected(row ->
                new ImportLogicalRow(row.sourceRowNumber() + 1, row.branches()),
                "source row identity");
        assertProcessedMutationRejected(row ->
                new ImportLogicalRow(row.sourceRowNumber(), List.of(row.branches().getFirst())),
                "artifact count");
        assertProcessedMutationRejected(row -> replacePrimary(row, primary ->
                new ImportArtifactBranch("other", primary.role(), primary.cells(),
                        primary.mergePolicies(), primary.requestedSlot(), primary.recordKey(),
                        primary.matchKeys())), "artifact branches");
        assertProcessedMutationRejected(row -> replacePrimary(row, primary ->
                new ImportArtifactBranch(primary.artifactName(), primary.role(), primary.cells(),
                        primary.mergePolicies(), java.util.OptionalLong.empty(), primary.recordKey(),
                        primary.matchKeys())), "admitted cells");
        assertProcessedMutationRejected(row -> replacePrimary(row, primary -> {
            var policies = new java.util.LinkedHashMap<>(primary.mergePolicies());
            policies.put("ip", ImportMergePolicy.KEEP_EXISTING);
            return new ImportArtifactBranch(primary.artifactName(), primary.role(), primary.cells(),
                    policies, primary.requestedSlot(), primary.recordKey(), primary.matchKeys());
        }), "admitted cells");
    }

    @Test
    void processedFinalizationRefusesSourceLabelReplacement() {
        DataframeImportCatalogDraft.Contract base = contract(
                ImportFormulaPolicy.REJECT, ImportProcessingMode.PROCESSED).definition();
        DataframeImportCatalogDraft.Artifact primary = base.artifacts().getFirst();
        var sourceBound = new DataframeImportCatalogDraft.Artifact(primary.name(), primary.role(),
                primary.recordKey(), primary.matchKeys(), primary.mergeDefault(), "description",
                null, primary.columns());
        CompiledDataframeImportContract contract = compiled(copyWith(base,
                List.of(sourceBound, base.artifacts().get(1)), base.requestedSlot()));
        DataframeImportRowMapper mapper = new DataframeImportRowMapper(
                (specification, value) -> value, keys,
                (configured, record, admitted) -> ImportRowMappingResult.accepted(
                        replacePrimary(admitted, branch -> {
                            var cells = new java.util.LinkedHashMap<>(branch.cells());
                            cells.put("description", ImportCell.value("forged source"));
                            return new ImportArtifactBranch(branch.artifactName(), branch.role(), cells,
                                    branch.mergePolicies(), branch.requestedSlot(), branch.recordKey(),
                                    branch.matchKeys());
                        })));

        assertThatThrownBy(() -> mapper.map(contract, new ImportDelimitedRecord(25, Map.of(
                "address", "192.0.2.25", "score", "25", "md5", "9".repeat(32),
                "external_id", "25", "note", "trusted source"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("source authority");
    }

    @Test
    void processedFinalizationRejectsInvalidDerivedValues() {
        DataframeImportCatalogDraft.Contract base = contract(
                ImportFormulaPolicy.REJECT, ImportProcessingMode.PROCESSED).definition();
        DataframeImportCatalogDraft.Artifact primary = base.artifacts().getFirst();
        var validated = new DataframeImportCatalogDraft.Artifact(primary.name(), primary.role(),
                primary.recordKey(), primary.matchKeys(), primary.mergeDefault(),
                List.of(new DataframeImportCatalogDraft.Column("ip", "address", List.of(), null, "bare-ip"),
                        primary.columns().get(1), primary.columns().get(2)));
        CompiledDataframeImportContract contract = compiled(copyWith(base,
                List.of(validated, base.artifacts().get(1)), base.requestedSlot()));
        DataframeImportRowMapper mapper = new DataframeImportRowMapper(
                (specification, value) -> value,
                (rule, value) -> !"bare-ip".equals(rule) || !value.startsWith("999."),
                keys,
                (configured, record, admitted) -> ImportRowMappingResult.accepted(
                        replacePrimary(admitted, branch -> {
                            var cells = new java.util.LinkedHashMap<>(branch.cells());
                            cells.put("ip", ImportCell.value("999.0.0.1"));
                            return new ImportArtifactBranch(branch.artifactName(), branch.role(), cells,
                                    branch.mergePolicies(), branch.requestedSlot(), branch.recordKey(),
                                    branch.matchKeys());
                        })));

        ImportRowMappingResult result = mapper.map(contract,
                record(26, "192.0.2.26", "26", "A".repeat(32), "26"));

        assertThat(result.row()).isEmpty();
        assertThat(result.issues()).extracting(ImportRowIssue::code)
                .contains("IMPORT.VALUE_INVALID");
    }

    @Test
    void processedFinalizationRejectsDerivedFormulaAndMissingIdentity() {
        DataframeImportRowMapper formulaMapper = mapperReplacingPrimaryCell(
                "description", ImportCell.value("=payload"));
        ImportRowMappingResult formula = formulaMapper.map(
                contract(ImportFormulaPolicy.REJECT, ImportProcessingMode.PROCESSED),
                record(28, "192.0.2.28", "28", "C".repeat(32), "28"));

        assertThat(formula.row()).isEmpty();
        assertThat(formula.issues()).extracting(ImportRowIssue::code)
                .contains("IMPORT.FORMULA_REJECTED");

        DataframeImportRowMapper missingKeyMapper = mapperReplacingPrimaryCell(
                "ip", ImportCell.nullValue());
        ImportRowMappingResult missingKey = missingKeyMapper.map(
                contract(ImportFormulaPolicy.REJECT, ImportProcessingMode.PROCESSED),
                record(29, "192.0.2.29", "29", "D".repeat(32), "29"));

        assertThat(missingKey.row()).isEmpty();
        assertThat(missingKey.issues()).extracting(ImportRowIssue::code)
                .contains("IMPORT.RECORD_KEY_MISSING");
    }

    @Test
    void processedInputRejectionDoesNotTurnIntoAnAcceptedWarning() {
        DataframeImportRowMapper mapper = new DataframeImportRowMapper(
                (specification, value) -> value, keys,
                (contract, record, admitted) -> ImportRowMappingResult.rejected(
                        List.of(new ImportRowIssue(record.sourceRowNumber(), "ip_list",
                                "IMPORT.PROCESSED_INPUT_INVALID"))));

        ImportRowMappingResult result = mapper.map(
                contract(ImportFormulaPolicy.REJECT, ImportProcessingMode.PROCESSED),
                record(30, "192.0.2.30", "30", "E".repeat(32), "30"));

        assertThat(result.row()).isEmpty();
        assertThat(result.warnings()).isEmpty();
        assertThat(result.issues()).extracting(ImportRowIssue::code)
                .containsExactly("IMPORT.PROCESSED_INPUT_INVALID");
    }

    private DataframeImportRowMapper mapperReplacingPrimaryCell(String target, ImportCell cell) {
        return new DataframeImportRowMapper((specification, value) -> value, keys,
                (contract, record, admitted) -> ImportRowMappingResult.accepted(
                        replacePrimary(admitted, branch -> {
                            var cells = new java.util.LinkedHashMap<>(branch.cells());
                            cells.put(target, cell);
                            return new ImportArtifactBranch(branch.artifactName(), branch.role(), cells,
                                    branch.mergePolicies(), branch.requestedSlot(), branch.recordKey(),
                                    branch.matchKeys());
                        })));
    }

    private void assertProcessedMutationRejected(UnaryOperator<ImportLogicalRow> mutation,
                                                 String message) {
        DataframeImportRowMapper mapper = new DataframeImportRowMapper(
                (specification, value) -> value, keys,
                (contract, record, admitted) -> ImportRowMappingResult.accepted(mutation.apply(admitted)));
        assertThatThrownBy(() -> mapper.map(
                contract(ImportFormulaPolicy.REJECT, ImportProcessingMode.PROCESSED),
                record(27, "192.0.2.27", "27", "B".repeat(32), "27")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(message);
    }

    private ImportLogicalRow replacePrimary(ImportLogicalRow row,
                                            UnaryOperator<ImportArtifactBranch> mutation) {
        var branches = new java.util.ArrayList<>(row.branches());
        branches.set(0, mutation.apply(branches.getFirst()));
        return new ImportLogicalRow(row.sourceRowNumber(), branches);
    }

    @Test
    void validatesInputValuesBeforeProcessingAndRowShapeAfterProcessing() {
        DataframeImportCatalogDraft.Contract base = contract(
                ImportFormulaPolicy.REJECT, ImportProcessingMode.PROCESSED).definition();
        DataframeImportCatalogDraft.Artifact primary = base.artifacts().getFirst();
        DataframeImportCatalogDraft.Artifact constrained = new DataframeImportCatalogDraft.Artifact(
                primary.name(), primary.role(), primary.recordKey(), primary.matchKeys(),
                primary.mergeDefault(), null, List.of("ip", "score"),
                List.of(
                        new DataframeImportCatalogDraft.Column(
                                "ip", "address", List.of("trim"), null, "bare-ip"),
                        new DataframeImportCatalogDraft.Column(
                                "score", "score", List.of("trim"), null),
                        new DataframeImportCatalogDraft.Column(
                                "description", "note", List.of(), null)));
        CompiledDataframeImportContract configured = compiled(copyWith(
                base, List.of(constrained, base.artifacts().get(1)), base.requestedSlot()));
        DataframeImportRowMapper mapper = new DataframeImportRowMapper(
                (specification, value) -> value.trim(),
                (rule, value) -> !"bare-ip".equals(rule) || !value.startsWith("999."),
                keys,
                (contract, record, mapped) -> ImportRowMappingResult.accepted(mapped));

        ImportRowMappingResult invalidValue = mapper.map(configured,
                record(21, "999.0.0.1", "", "5".repeat(32), "21"));
        ImportRowMappingResult compound = mapper.map(configured,
                record(22, "192.0.2.22", "22", "6".repeat(32), "22"));

        assertThat(invalidValue.row()).isEmpty();
        assertThat(invalidValue.issues()).extracting(ImportRowIssue::code)
                .containsExactly("IMPORT.VALUE_INVALID");
        assertThat(compound.row()).isEmpty();
        assertThat(compound.issues()).extracting(ImportRowIssue::code)
                .contains("IMPORT.NONEMPTY_CARDINALITY");
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
