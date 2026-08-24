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
import com.iocextractor.application.port.out.dataframeimport.ImportValueTransformRegistry;
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

    private final ImportValueTransformRegistry transforms;
    private final CanonicalArtifactKeyResolver keyResolver;
    private final ProcessedImportRowPreparer processed;

    /** Creates a mapper using framework-free transform and canonical-key collaborators. */
    public DataframeImportRowMapper(ImportValueTransformRegistry transforms,
                                    CanonicalArtifactKeyResolver keyResolver) {
        this(transforms, keyResolver, (contract, record, mapped) -> {
            throw new ImportRowMappingException(
                    ImportRowMappingException.Reason.PROCESSED_MODE_UNAVAILABLE,
                    "Processed import requires the dedicated preparation strategy");
        });
    }

    /** Creates a mapper with both declarative and ordinary-policy preparation strategies. */
    public DataframeImportRowMapper(ImportValueTransformRegistry transforms,
                                    CanonicalArtifactKeyResolver keyResolver,
                                    ProcessedImportRowPreparer processed) {
        this.transforms = Objects.requireNonNull(transforms, "transforms");
        this.keyResolver = Objects.requireNonNull(keyResolver, "keyResolver");
        this.processed = Objects.requireNonNull(processed, "processed");
    }

    /** Maps one source row atomically across all declared artifact branches. */
    public ImportRowMappingResult map(CompiledDataframeImportContract contract,
                                      ImportDelimitedRecord record) {
        Objects.requireNonNull(contract, "contract");
        Objects.requireNonNull(record, "record");
        List<ImportRowIssue> issues = new ArrayList<>();
        List<ImportArtifactBranch> branches = new ArrayList<>(contract.definition().artifacts().size());
        for (DataframeImportCatalogDraft.Artifact artifact : contract.definition().artifacts()) {
            Map<String, ImportCell> cells = cells(contract, artifact, record, issues);
            Map<String, ImportMergePolicy> mergePolicies = mergePolicies(contract, artifact);
            OptionalLong requestedSlot = requestedSlot(contract, artifact, record, issues);
            ArtifactRow keyRow = ArtifactRow.ordered(values(cells));
            Optional<CanonicalKeyMaterial> recordKey = keyResolver.recordKeyOf(artifact.name(), keyRow);
            if (recordKey.isEmpty()) {
                issues.add(issue(record, artifact.name(), RECORD_KEY_MISSING));
            }
            List<CanonicalKeyMaterial> matchKeys = keyResolver.matchKeysOf(artifact.name(), keyRow).stream()
                    .filter(key -> artifact.matchKeys().contains(key.definitionId()))
                    .toList();
            branches.add(new ImportArtifactBranch(
                    artifact.name(), artifact.role(), cells, mergePolicies,
                    requestedSlot, recordKey, matchKeys));
        }
        if (!issues.isEmpty()) {
            return ImportRowMappingResult.rejected(issues);
        }
        ImportLogicalRow mapped = new ImportLogicalRow(record.sourceRowNumber(), branches);
        return contract.definition().mode() == ImportProcessingMode.AS_IS
                ? ImportRowMappingResult.accepted(mapped)
                : processed.prepare(contract, record, mapped);
    }

    private Map<String, ImportMergePolicy> mergePolicies(
            CompiledDataframeImportContract contract,
            DataframeImportCatalogDraft.Artifact artifact) {
        Map<String, ImportMergePolicy> policies = new LinkedHashMap<>();
        for (DataframeImportCatalogDraft.Column column : artifact.columns()) {
            ImportMergePolicy policy = column.mergePolicy() != null
                    ? column.mergePolicy()
                    : artifact.mergeDefault() != null
                            ? artifact.mergeDefault()
                            : contract.definition().mergeDefault();
            policies.put(column.target(), Objects.requireNonNull(policy, "effective import merge policy"));
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
        if (value.isEmpty()) {
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
        return ImportCell.value(value);
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
