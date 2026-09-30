package com.iocextractor.application.dataframeimport.mapping;


import com.iocextractor.application.artifact.ArtifactRow;
import com.iocextractor.application.artifact.CanonicalArtifactKeyResolver;
import com.iocextractor.application.artifact.CanonicalKeyMaterial;
import com.iocextractor.application.dataframeimport.contract.CompiledDataframeImportContract;
import com.iocextractor.application.dataframeimport.contract.DataframeImportCatalogDraft;
import com.iocextractor.application.dataframeimport.model.ImportArtifactBranch;
import com.iocextractor.application.dataframeimport.model.ImportArtifactRole;
import com.iocextractor.application.dataframeimport.model.ImportCell;
import com.iocextractor.application.dataframeimport.model.ImportDelimitedRecord;
import com.iocextractor.application.dataframeimport.model.ImportFormulaPolicy;
import com.iocextractor.application.dataframeimport.model.ImportLogicalRow;
import com.iocextractor.application.dataframeimport.model.ImportMergePolicy;
import com.iocextractor.application.dataframeimport.model.ImportProcessingMode;
import com.iocextractor.application.dataframeimport.model.ImportRowIssue;
import com.iocextractor.application.dataframeimport.model.ImportRowWarning;
import com.iocextractor.application.port.out.dataframeimport.ImportValueTransformRegistry;
import com.iocextractor.application.port.out.dataframeimport.ImportValueValidatorRegistry;
import com.iocextractor.application.port.out.dataframeimport.ProcessedImportRowPreparer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/** Strict declarative tri-state mapper with deterministic branch fan-out. */
public final class DataframeImportRowMapper {

    private static final String TRANSFORM_FAILED = "IMPORT.TRANSFORM_FAILED";
    private static final String FORMULA_REJECTED = "IMPORT.FORMULA_REJECTED";
    private static final String REQUESTED_SLOT_INVALID = "IMPORT.REQUESTED_SLOT_INVALID";
    private static final String RECORD_KEY_MISSING = "IMPORT.RECORD_KEY_MISSING";
    private static final String VALUE_INVALID = "IMPORT.VALUE_INVALID";
    private static final String NONEMPTY_CARDINALITY = "IMPORT.NONEMPTY_CARDINALITY";

    private final ImportValueTransformRegistry transforms;
    private final ImportValueValidatorRegistry validators;
    private final CanonicalArtifactKeyResolver keyResolver;
    private final ProcessedImportRowPreparer processed;

    /** Creates a mapper using framework-free transform and canonical-key collaborators. */
    public DataframeImportRowMapper(ImportValueTransformRegistry transforms,
                                    CanonicalArtifactKeyResolver keyResolver) {
        this(transforms, (rule, value) -> true, keyResolver, (contract, record, mapped) -> {
            throw new ImportRowMappingException(
                    ImportRowMappingException.Reason.PROCESSED_MODE_UNAVAILABLE,
                    "Processed import requires the dedicated preparation strategy");
        });
    }

    /** Creates a mapper with both declarative and ordinary-policy preparation strategies. */
    public DataframeImportRowMapper(ImportValueTransformRegistry transforms,
                                    CanonicalArtifactKeyResolver keyResolver,
                                    ProcessedImportRowPreparer processed) {
        this(transforms, (rule, value) -> true, keyResolver, processed);
    }

    /** Creates a mapper with declarative transforms, validators and processed preparation. */
    public DataframeImportRowMapper(ImportValueTransformRegistry transforms,
                                    ImportValueValidatorRegistry validators,
                                    CanonicalArtifactKeyResolver keyResolver,
                                    ProcessedImportRowPreparer processed) {
        this.transforms = Objects.requireNonNull(transforms, "transforms");
        this.validators = Objects.requireNonNull(validators, "validators");
        this.keyResolver = Objects.requireNonNull(keyResolver, "keyResolver");
        this.processed = Objects.requireNonNull(processed, "processed");
    }

    /** Admits one source row, then validates identity only on the final output fields. */
    public ImportRowMappingResult map(CompiledDataframeImportContract contract,
                                      ImportDelimitedRecord record) {
        ImportRowMappingResult admitted = admit(contract, record);
        if (admitted.row().isEmpty()) {
            return admitted;
        }
        if (contract.definition().mode() == ImportProcessingMode.AS_IS) {
            return admitted;
        }
        ImportRowMappingResult prepared = processed.prepare(contract, record, admitted.row().orElseThrow());
        return prepared.row().isEmpty() ? prepared
                : finalizeRow(contract, record, admitted.row().orElseThrow(),
                        prepared.row().orElseThrow(), prepared.warnings());
    }

    /** Applies declared input transforms and validation without interpreting output identity. */
    public ImportRowMappingResult admit(CompiledDataframeImportContract contract,
                                        ImportDelimitedRecord record) {
        Objects.requireNonNull(contract, "contract");
        Objects.requireNonNull(record, "record");
        List<ImportRowIssue> issues = new ArrayList<>();
        List<ImportArtifactBranch> branches = new ArrayList<>(contract.definition().artifacts().size());
        for (DataframeImportCatalogDraft.Artifact artifact : contract.definition().artifacts()) {
            Map<String, ImportCell> cells = cells(contract, artifact, record, issues);
            Map<String, ImportMergePolicy> mergePolicies = mergePolicies(contract, artifact);
            OptionalLong requestedSlot = requestedSlot(contract, artifact, record, issues);
            if (contract.definition().mode() == ImportProcessingMode.AS_IS) {
                validateRowShape(artifact, cells, record, issues);
            }
            ArtifactRow keyRow = ArtifactRow.ordered(values(cells));
            Optional<CanonicalKeyMaterial> recordKey = contract.definition().mode() == ImportProcessingMode.AS_IS
                    ? keyResolver.recordKeyOf(artifact.name(), keyRow) : Optional.empty();
            if (contract.definition().mode() == ImportProcessingMode.AS_IS && recordKey.isEmpty()) {
                issues.add(issue(record, artifact.name(), RECORD_KEY_MISSING));
            }
            List<CanonicalKeyMaterial> matchKeys = contract.definition().mode() == ImportProcessingMode.AS_IS
                    ? keyResolver.matchKeysOf(artifact.name(), keyRow).stream()
                            .filter(key -> artifact.matchKeys().contains(key.definitionId())).toList()
                    : List.of();
            branches.add(new ImportArtifactBranch(
                    artifact.name(), artifact.role(), cells, mergePolicies,
                    requestedSlot, recordKey, matchKeys));
        }
        if (!issues.isEmpty()) {
            return ImportRowMappingResult.rejected(issues);
        }
        return ImportRowMappingResult.accepted(new ImportLogicalRow(record.sourceRowNumber(), branches));
    }

    private ImportRowMappingResult finalizeRow(CompiledDataframeImportContract contract,
                                               ImportDelimitedRecord record,
                                               ImportLogicalRow admittedRow,
                                               ImportLogicalRow prepared,
                                               List<ImportRowWarning> warnings) {
        List<ImportRowIssue> issues = new ArrayList<>();
        List<ImportArtifactBranch> branches = new ArrayList<>(prepared.branches().size());
        if (prepared.sourceRowNumber() != admittedRow.sourceRowNumber()) {
            throw new IllegalStateException("Processed import changed the source row identity");
        }
        if (prepared.branches().size() != contract.definition().artifacts().size()) {
            throw new IllegalStateException("Processed import changed the authorized artifact count");
        }
        for (int index = 0; index < contract.definition().artifacts().size(); index++) {
            DataframeImportCatalogDraft.Artifact artifact = contract.definition().artifacts().get(index);
            ImportArtifactBranch branch = prepared.branches().get(index);
            if (!artifact.name().equals(branch.artifactName()) || artifact.role() != branch.role()) {
                throw new IllegalStateException("Processed import changed the authorized artifact branches");
            }
            ImportArtifactBranch admitted = admittedRow.branches().get(index);
            if (!admitted.requestedSlot().equals(branch.requestedSlot())
                    || !branch.cells().keySet().containsAll(admitted.cells().keySet())
                    || admitted.mergePolicies().entrySet().stream().anyMatch(entry ->
                            branch.mergePolicies().get(entry.getKey()) != entry.getValue())
                    || artifact.sourceLabelTarget() != null
                            && !processed.authorizesSourceLabel(contract, artifact.name(),
                                    artifact.sourceLabelTarget(),
                                    admitted.cells().get(artifact.sourceLabelTarget()),
                                    branch.cells().get(artifact.sourceLabelTarget()))) {
                throw new IllegalStateException("Processed import changed admitted cells or source authority");
            }
            validateFinalCells(contract, artifact, branch.cells(), record, issues);
            validateRowShape(artifact, branch.cells(), record, issues);
            ArtifactRow keyRow = ArtifactRow.ordered(values(branch.cells()));
            Optional<CanonicalKeyMaterial> recordKey = keyResolver.recordKeyOf(artifact.name(), keyRow);
            if (recordKey.isEmpty()) {
                issues.add(issue(record, artifact.name(), RECORD_KEY_MISSING));
            }
            List<CanonicalKeyMaterial> matchKeys = keyResolver.matchKeysOf(artifact.name(), keyRow).stream()
                    .filter(key -> artifact.matchKeys().contains(key.definitionId())).toList();
            branches.add(new ImportArtifactBranch(artifact.name(), artifact.role(), branch.cells(),
                    branch.mergePolicies(), branch.requestedSlot(), recordKey, matchKeys));
        }
        return issues.isEmpty()
                ? ImportRowMappingResult.accepted(
                        new ImportLogicalRow(record.sourceRowNumber(), branches), warnings)
                : ImportRowMappingResult.rejected(issues);
    }

    private void validateFinalCells(CompiledDataframeImportContract contract,
                                    DataframeImportCatalogDraft.Artifact artifact,
                                    Map<String, ImportCell> cells,
                                    ImportDelimitedRecord record,
                                    List<ImportRowIssue> issues) {
        for (DataframeImportCatalogDraft.Column column : artifact.columns()) {
            ImportCell cell = cells.get(column.target());
            if (cell == null || cell.presence() != ImportCell.Presence.VALUE) {
                continue;
            }
            if (contract.definition().formulaPolicy() == ImportFormulaPolicy.REJECT
                    && formulaDangerous(cell.value())) {
                issues.add(issue(record, artifact.name(), FORMULA_REJECTED));
            }
            if (column.validation() != null && !validators.isValid(column.validation(), cell.value())) {
                issues.add(issue(record, artifact.name(), VALUE_INVALID));
            }
        }
    }

    private Map<String, ImportMergePolicy> mergePolicies(
            CompiledDataframeImportContract contract,
            DataframeImportCatalogDraft.Artifact artifact) {
        Map<String, ImportMergePolicy> policies = new LinkedHashMap<>();
        for (DataframeImportCatalogDraft.Column column : artifact.columns()) {
            policies.put(column.target(), Objects.requireNonNull(
                    ImportMergePolicyResolver.resolve(contract, artifact, column),
                    "effective import merge policy"));
        }
        return policies;
    }

    private Map<String, ImportCell> cells(CompiledDataframeImportContract contract,
                                          DataframeImportCatalogDraft.Artifact artifact,
                                          ImportDelimitedRecord record,
                                          List<ImportRowIssue> issues) {
        Map<String, ImportCell> cells = new LinkedHashMap<>();
        for (DataframeImportCatalogDraft.Column column : artifact.columns()) {
            ImportCell cell = cell(contract, artifact.name(), column, record, issues);
            cells.put(column.target(), cell);
        }
        return cells;
    }

    private ImportCell cell(CompiledDataframeImportContract contract,
                            String artifact,
                            DataframeImportCatalogDraft.Column column,
                            ImportDelimitedRecord record,
                            List<ImportRowIssue> issues) {
        if (!record.values().containsKey(column.source())) {
            return ImportCell.absent();
        }
        String value = record.values().get(column.source());
        if (value.isEmpty() || contract.dialect().nullLiterals().contains(value)) {
            return ImportCell.nullValue();
        }
        try {
            for (String specification : column.transforms()) {
                value = Objects.requireNonNull(
                        transforms.transform(specification, value), "import transform result");
            }
        } catch (ImportValueMappingException failure) {
            issues.add(issue(record, artifact, TRANSFORM_FAILED));
            return ImportCell.absent();
        }
        if (value.isEmpty() || contract.dialect().nullLiterals().contains(value)) {
            return ImportCell.nullValue();
        }
        if (contract.definition().formulaPolicy() == ImportFormulaPolicy.REJECT && formulaDangerous(value)) {
            issues.add(issue(record, artifact, FORMULA_REJECTED));
            return ImportCell.absent();
        }
        if (column.validation() != null && !validators.isValid(column.validation(), value)) {
            issues.add(issue(record, artifact, VALUE_INVALID));
            return ImportCell.absent();
        }
        return ImportCell.value(value);
    }

    private void validateRowShape(DataframeImportCatalogDraft.Artifact artifact,
                                  Map<String, ImportCell> cells,
                                  ImportDelimitedRecord record,
                                  List<ImportRowIssue> issues) {
        if (artifact.exactlyOneNonempty() == null) {
            return;
        }
        long populated = artifact.exactlyOneNonempty().stream()
                .map(cells::get)
                .filter(Objects::nonNull)
                .filter(cell -> cell.presence() == ImportCell.Presence.VALUE && !cell.value().isBlank())
                .count();
        if (populated != 1) {
            issues.add(issue(record, artifact.name(), NONEMPTY_CARDINALITY));
        }
    }

    private OptionalLong requestedSlot(CompiledDataframeImportContract contract,
                                       DataframeImportCatalogDraft.Artifact artifact,
                                       ImportDelimitedRecord record,
                                       List<ImportRowIssue> issues) {
        DataframeImportCatalogDraft.RequestedSlot requested = contract.definition().requestedSlot();
        if (requested == null || artifact.role() != ImportArtifactRole.PRIMARY
                || !record.values().containsKey(requested.sourceColumn())) {
            return OptionalLong.empty();
        }
        String raw = record.values().get(requested.sourceColumn());
        if (raw.isEmpty() || contract.dialect().nullLiterals().contains(raw)) {
            return OptionalLong.empty();
        }
        try {
            long value = Long.parseLong(raw);
            if (value < 1) {
                throw new NumberFormatException("non-positive");
            }
            return OptionalLong.of(value);
        } catch (NumberFormatException failure) {
            issues.add(issue(record, artifact.name(), REQUESTED_SLOT_INVALID));
            return OptionalLong.empty();
        }
    }

    private Map<String, String> values(Map<String, ImportCell> cells) {
        Map<String, String> values = new LinkedHashMap<>();
        cells.forEach((column, cell) -> values.put(column,
                cell.presence() == ImportCell.Presence.VALUE ? cell.value() : null));
        return values;
    }

    private boolean formulaDangerous(String value) {
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (!Character.isWhitespace(character)) {
                return character == '=' || character == '+' || character == '-'
                        || character == '@';
            }
        }
        return false;
    }

    private ImportRowIssue issue(ImportDelimitedRecord record, String artifact, String code) {
        return new ImportRowIssue(record.sourceRowNumber(), artifact, code);
    }
}
