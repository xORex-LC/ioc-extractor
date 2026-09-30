package com.iocextractor.adapter.in.csv;


import com.iocextractor.processing.mapping.ColumnSpec;
import com.iocextractor.processing.mapping.ConfigurableRowMapper;
import com.iocextractor.adapter.out.sink.csv.CsvArtifactDefinition;
import com.iocextractor.processing.mapping.RowMappingException;
import com.iocextractor.processing.classification.IndicatorClassifier;
import com.iocextractor.application.dataframeimport.contract.CompiledDataframeImportContract;
import com.iocextractor.application.dataframeimport.contract.DataframeImportCatalogDraft;
import com.iocextractor.application.dataframeimport.mapping.ImportRowMappingResult;
import com.iocextractor.application.dataframeimport.mapping.ImportMergePolicyResolver;
import com.iocextractor.application.dataframeimport.model.ImportArtifactBranch;
import com.iocextractor.application.dataframeimport.model.ImportCell;
import com.iocextractor.application.dataframeimport.model.ImportDelimitedRecord;
import com.iocextractor.application.dataframeimport.model.ImportLogicalRow;
import com.iocextractor.application.dataframeimport.model.ImportMergePolicy;
import com.iocextractor.application.dataframeimport.model.ImportRowIssue;
import com.iocextractor.processing.model.ClassifiedIndicator;
import com.iocextractor.application.port.out.dataframeimport.ProcessedImportRowPreparer;
import com.iocextractor.domain.extract.IndicatorExtractor;
import com.iocextractor.domain.extract.RawIndicator;
import com.iocextractor.domain.feature.NetworkAddressParser;
import com.iocextractor.domain.model.Indicator;
import com.iocextractor.domain.model.SourceContext;
import com.iocextractor.domain.refang.Refanger;
import com.iocextractor.processing.parse.ExactIndicatorParser;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Applies the ordinary CSV artifact policy to explicit {@code processed} import rows. */
public final class CsvProcessedImportRowPreparer implements ProcessedImportRowPreparer {

    private static final Set<String> IOC_PROVIDERS = Set.of("value", "address.url", "address.ip");
    private static final Set<String> NON_DERIVED_PROVIDERS = Set.of("const", "id");
    private static final String INPUT_INVALID = "IMPORT.PROCESSED_INPUT_INVALID";
    private static final String UNROUTABLE = "IMPORT.PROCESSED_VALUE_UNROUTABLE";
    private static final String DERIVATION_FAILED = "IMPORT.PROCESSED_DERIVATION_FAILED";
    private static final String COMPOUND_CONFLICT = "IMPORT.PROCESSED_COMPOUND_CONFLICT";

    private final Map<String, CsvArtifactDefinition> definitions;
    private final Refanger refanger;
    private final ExactIndicatorParser parser;
    private final IndicatorClassifier classifier;

    /** Creates a bounded row-local preparation strategy from the ordinary runtime policy. */
    public CsvProcessedImportRowPreparer(List<CsvArtifactDefinition> definitions,
                                         Refanger refanger,
                                         IndicatorExtractor extractor,
                                         IndicatorClassifier classifier) {
        Objects.requireNonNull(definitions, "definitions");
        Map<String, CsvArtifactDefinition> byName = new LinkedHashMap<>();
        for (CsvArtifactDefinition definition : definitions) {
            CsvArtifactDefinition previous = byName.put(definition.name(), definition);
            if (previous != null) {
                throw new IllegalArgumentException("Duplicate processed artifact: " + definition.name());
            }
            if (!(definition.mapper() instanceof ConfigurableRowMapper)) {
                throw new IllegalArgumentException(
                        "Processed import requires configurable row mapping: " + definition.name());
            }
        }
        this.definitions = Map.copyOf(byName);
        this.refanger = Objects.requireNonNull(refanger, "refanger");
        this.parser = new ExactIndicatorParser(Objects.requireNonNull(extractor, "extractor"),
                new NetworkAddressParser());
        this.classifier = Objects.requireNonNull(classifier, "classifier");
    }

    @Override
    public ImportRowMappingResult prepare(CompiledDataframeImportContract contract,
                                          ImportDelimitedRecord record,
                                          ImportLogicalRow mapped) {
        List<ImportRowIssue> issues = new ArrayList<>();
        List<ImportArtifactBranch> branches = new ArrayList<>(mapped.branches().size());
        for (ImportArtifactBranch branch : mapped.branches()) {
            DataframeImportCatalogDraft.Artifact contractArtifact = contract.definition().artifacts().stream()
                    .filter(candidate -> candidate.name().equals(branch.artifactName()))
                    .findFirst()
                    .orElseThrow();
            CsvArtifactDefinition definition = Objects.requireNonNull(
                    definitions.get(branch.artifactName()), "processed artifact definition");
            ConfigurableRowMapper rowMapper = (ConfigurableRowMapper) definition.mapper();
            List<ClassifiedIndicator> indicators = indicators(
                    record, branch, contractArtifact, rowMapper, issues);
            List<List<String>> prepared = prepare(definition, rowMapper, indicators, record, issues);
            Map<String, ImportCell> cells = new LinkedHashMap<>(branch.cells());
            Map<String, ImportMergePolicy> policies = new LinkedHashMap<>(branch.mergePolicies());
            replaceDerivedCells(contract, contractArtifact, rowMapper, prepared, cells, policies, record, issues);
            branches.add(new ImportArtifactBranch(
                    branch.artifactName(), branch.role(), cells, policies, branch.requestedSlot(),
                    java.util.Optional.empty(), List.of()));
        }
        return issues.isEmpty()
                ? ImportRowMappingResult.accepted(new ImportLogicalRow(record.sourceRowNumber(), branches))
                : ImportRowMappingResult.rejected(issues);
    }

    @Override
    public boolean authorizesSourceLabel(CompiledDataframeImportContract contract,
                                         String artifact, String target,
                                         ImportCell admitted, ImportCell prepared) {
        if (admitted == null || admitted.presence() != ImportCell.Presence.VALUE) {
            return Objects.equals(admitted, prepared);
        }
        CsvArtifactDefinition definition = Objects.requireNonNull(definitions.get(artifact),
                "processed artifact definition");
        ConfigurableRowMapper mapper = (ConfigurableRowMapper) definition.mapper();
        String mapped = mapper.mapSourceLabel(target, admitted.value());
        return Objects.equals(mapped == null ? ImportCell.nullValue() : ImportCell.value(mapped), prepared);
    }

    private List<ClassifiedIndicator> indicators(ImportDelimitedRecord record,
                                                  ImportArtifactBranch branch,
                                                  DataframeImportCatalogDraft.Artifact contractArtifact,
                                                  ConfigurableRowMapper mapper,
                                                  List<ImportRowIssue> issues) {
        List<ClassifiedIndicator> indicators = new ArrayList<>();
        int issueCountBeforeBranch = issues.size();
        String source = sourceLabel(branch.cells(), contractArtifact, mapper.columns());
        for (ColumnSpec column : mapper.columns()) {
            if (!IOC_PROVIDERS.contains(column.from())) {
                continue;
            }
            ImportCell cell = branch.cells().get(column.name());
            if (cell == null || cell.presence() != ImportCell.Presence.VALUE) {
                continue;
            }
            String processed = refanger.refang(cell.value()).text();
            ExactIndicatorParser.Result parsed = parser.parse(processed);
            if (!parsed.isAvailable()) {
                issues.add(issue(record, branch.artifactName(), INPUT_INVALID));
                continue;
            }
            RawIndicator raw = parsed.indicator();
            Indicator indicator = new Indicator(raw.value(), raw.type(), new SourceContext(source, null));
            if (!classifier.supports(indicator)) {
                issues.add(issue(record, branch.artifactName(), INPUT_INVALID));
                continue;
            }
            ClassifiedIndicator classified = new ClassifiedIndicator(indicator, classifier.classify(indicator));
            if (!mapper.applies(column, classified)) {
                issues.add(issue(record, branch.artifactName(), INPUT_INVALID));
                continue;
            }
            indicators.add(classified);
        }
        if (indicators.isEmpty() && issues.size() == issueCountBeforeBranch) {
            issues.add(issue(record, branch.artifactName(), INPUT_INVALID));
        }
        return indicators;
    }

    private List<List<String>> prepare(CsvArtifactDefinition definition,
                                       ConfigurableRowMapper mapper,
                                       List<ClassifiedIndicator> indicators,
                                       ImportDelimitedRecord record,
                                       List<ImportRowIssue> issues) {
        List<List<String>> rows = new ArrayList<>();
        for (ClassifiedIndicator indicator : indicators) {
            if (!definition.accepts().contains(indicator.indicator().type())
                    || !definition.filter().accepts(indicator)) {
                continue;
            }
            try {
                rows.add(mapper.toRow(indicator));
            } catch (RowMappingException failure) {
                issues.add(issue(record, definition.name(), DERIVATION_FAILED));
            }
        }
        if (rows.isEmpty() && !indicators.isEmpty()) {
            issues.add(issue(record, definition.name(), UNROUTABLE));
        }
        return rows;
    }

    private void replaceDerivedCells(CompiledDataframeImportContract contract,
                                     DataframeImportCatalogDraft.Artifact artifact,
                                     ConfigurableRowMapper mapper,
                                     List<List<String>> prepared,
                                     Map<String, ImportCell> cells,
                                     Map<String, ImportMergePolicy> policies,
                                     ImportDelimitedRecord record,
                                     List<ImportRowIssue> issues) {
        List<ColumnSpec> columns = mapper.columns();
        for (int columnIndex = 0; columnIndex < columns.size(); columnIndex++) {
            ColumnSpec column = columns.get(columnIndex);
            if (NON_DERIVED_PROVIDERS.contains(column.from())) {
                continue;
            }
            Set<String> values = new LinkedHashSet<>();
            for (List<String> row : prepared) {
                String value = row.get(columnIndex);
                if (value != null) {
                    values.add(value);
                }
            }
            if (values.size() > 1) {
                issues.add(issue(record, artifact.name(), COMPOUND_CONFLICT));
                continue;
            }
            if (column.name().equals(artifact.sourceLabelTarget())
                    && (cells.get(column.name()) == null
                            || cells.get(column.name()).presence() != ImportCell.Presence.VALUE
                            || values.isEmpty())) {
                continue;
            }
            cells.put(column.name(), values.isEmpty()
                    ? ImportCell.nullValue()
                    : ImportCell.value(values.iterator().next()));
            policies.putIfAbsent(column.name(),
                    ImportMergePolicyResolver.resolve(contract, artifact, column.name()));
        }
    }

    private String sourceLabel(Map<String, ImportCell> cells,
                               DataframeImportCatalogDraft.Artifact contractArtifact,
                               List<ColumnSpec> columns) {
        List<String> inferredTargets = columns.stream()
                .filter(column -> "source.label".equals(column.from()))
                .map(ColumnSpec::name)
                .toList();
        String explicit = contractArtifact.sourceLabelTarget();
        if (explicit != null && !explicit.isBlank()) {
            return cellValue(cells.get(explicit));
        }
        if (inferredTargets.size() > 1) {
            throw new IllegalStateException("Processed import has ambiguous source.label bindings");
        }
        if (inferredTargets.isEmpty()) {
            return null;
        }
        return cellValue(cells.get(inferredTargets.getFirst()));
    }

    private String cellValue(ImportCell source) {
        return source != null && source.presence() == ImportCell.Presence.VALUE
                ? source.value() : null;
    }

    private ImportRowIssue issue(ImportDelimitedRecord record, String artifact, String code) {
        return new ImportRowIssue(record.sourceRowNumber(), artifact, code);
    }
}
