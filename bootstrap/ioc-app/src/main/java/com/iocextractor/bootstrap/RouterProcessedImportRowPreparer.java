package com.iocextractor.bootstrap;

import com.iocextractor.application.artifact.RoutedArtifactCandidate;
import com.iocextractor.application.dataframeimport.contract.CompiledDataframeImportContract;
import com.iocextractor.application.dataframeimport.contract.DataframeImportCatalogDraft;
import com.iocextractor.application.dataframeimport.mapping.ImportRowMappingResult;
import com.iocextractor.application.dataframeimport.mapping.ImportMergePolicyResolver;
import com.iocextractor.application.dataframeimport.model.ImportArtifactBranch;
import com.iocextractor.application.dataframeimport.model.ImportArtifactRole;
import com.iocextractor.application.dataframeimport.model.ImportCell;
import com.iocextractor.application.dataframeimport.model.ImportDelimitedRecord;
import com.iocextractor.application.dataframeimport.model.ImportLogicalRow;
import com.iocextractor.application.dataframeimport.model.ImportMergePolicy;
import com.iocextractor.application.dataframeimport.model.ImportRowIssue;
import com.iocextractor.application.dataframeimport.model.ImportRowWarning;
import com.iocextractor.application.observation.OccurrencePosition;
import com.iocextractor.application.port.out.dataframeimport.ProcessedImportRowPreparer;
import com.iocextractor.diagnostics.DiagnosticSeverity;
import com.iocextractor.domain.extract.IndicatorExtractor;
import com.iocextractor.domain.feature.NetworkAddressParser;
import com.iocextractor.domain.model.Indicator;
import com.iocextractor.domain.model.SourceContext;
import com.iocextractor.domain.refang.Refanger;
import com.iocextractor.processing.classification.IndicatorClassifier;
import com.iocextractor.processing.model.ClassifiedIndicator;
import com.iocextractor.processing.parse.ExactIndicatorParser;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Adapts explicitly bound import cells to the admitted IOC route, then assembles
 * one authorized logical row. Canonical identity remains with the application.
 */
final class RouterProcessedImportRowPreparer implements ProcessedImportRowPreparer {
    private static final String INPUT_INVALID = "IMPORT.PROCESSED_INPUT_INVALID";
    private static final String UNROUTABLE = "IMPORT.PROCESSED_VALUE_UNROUTABLE";
    private static final String COMPOUND_CONFLICT = "IMPORT.PROCESSED_COMPOUND_CONFLICT";
    private static final String DERIVATION_FAILED = "IMPORT.PROCESSED_DERIVATION_FAILED";
    private static final String VIEW_FALLBACK = "IMPORT.PROCESSED_VIEW_FALLBACK";
    private static final int MAX_INPUTS = 32;

    /** One authorized semantic carrier, identified without provider-name inference. */
    record Input(String artifact, String target) {
        Input {
            if (artifact == null || artifact.isBlank() || target == null || target.isBlank()) {
                throw new IllegalArgumentException("Processed import input requires artifact and target");
            }
        }
    }

    private final String contractId;
    private final List<Input> inputs;
    private final Map<String, Set<String>> outputTargets;
    private final Refanger refanger;
    private final ExactIndicatorParser parser;
    private final IndicatorClassifier classifier;
    private final IocProcessingRouteAdapter route;

    RouterProcessedImportRowPreparer(String contractId, List<Input> inputs,
                                     Map<String, Set<String>> outputTargets,
                                     Refanger refanger, IndicatorExtractor extractor,
                                     IndicatorClassifier classifier, IocProcessingRouteAdapter route) {
        if (contractId == null || contractId.isBlank()) {
            throw new IllegalArgumentException("Processed import binding requires a contract ID");
        }
        this.contractId = contractId;
        this.inputs = List.copyOf(inputs);
        if (this.inputs.isEmpty() || this.inputs.size() > MAX_INPUTS
                || new LinkedHashSet<>(this.inputs).size() != this.inputs.size()) {
            throw new IllegalArgumentException("Processed import inputs must be bounded and unique");
        }
        Map<String, Set<String>> targets = new LinkedHashMap<>();
        outputTargets.forEach((artifact, fields) -> {
            if (artifact == null || artifact.isBlank() || fields == null || fields.isEmpty()) {
                throw new IllegalArgumentException("Processed import outputs require artifact and fields");
            }
            if (fields.stream().anyMatch(field -> field == null || field.isBlank())) {
                throw new IllegalArgumentException("Processed import output fields must be non-blank");
            }
            targets.put(artifact, java.util.Collections.unmodifiableSet(new LinkedHashSet<>(fields)));
        });
        this.outputTargets = java.util.Collections.unmodifiableMap(targets);
        this.refanger = Objects.requireNonNull(refanger, "refanger");
        this.parser = new ExactIndicatorParser(Objects.requireNonNull(extractor, "extractor"),
                new NetworkAddressParser());
        this.classifier = Objects.requireNonNull(classifier, "classifier");
        this.route = Objects.requireNonNull(route, "route");
    }

    @Override
    public ImportRowMappingResult prepare(CompiledDataframeImportContract contract,
                                          ImportDelimitedRecord record, ImportLogicalRow admitted) {
        requireBinding(contract, admitted);
        RowAssembly assembly = new RowAssembly();
        for (Input input : inputs) {
            prepareInput(contract, record, admitted, input, assembly);
        }
        requirePrimaryOutput(record, admitted, assembly);
        if (!assembly.issues.isEmpty()) {
            return ImportRowMappingResult.rejected(assembly.issues);
        }
        return ImportRowMappingResult.accepted(assembledRow(contract, record, admitted, assembly),
                assembly.warnings);
    }

    private void prepareInput(CompiledDataframeImportContract contract, ImportDelimitedRecord record,
                              ImportLogicalRow admitted, Input input, RowAssembly assembly) {
        ImportArtifactBranch branch = branch(admitted, input.artifact());
        ImportCell cell = Objects.requireNonNull(branch.cells().get(input.target()),
                "admitted semantic input " + input);
        if (cell.presence() != ImportCell.Presence.VALUE) {
            return;
        }
        var parsed = parser.parse(refanger.refang(cell.value()).text());
        if (!parsed.isAvailable()) {
            assembly.issues.add(issue(record, input.artifact(), INPUT_INVALID));
            return;
        }
        Indicator indicator = new Indicator(parsed.indicator().value(), parsed.indicator().type(),
                new SourceContext(sourceLabel(contract, branch), null));
        var original = new ProcessingView(new ClassifiedIndicator(indicator, classifier.classify(indicator)),
                new OccurrencePosition(record.sourceRowNumber()), 0);
        var result = route.prepare(original);
        result.diagnostics().forEach(diagnostic -> {
            if (diagnostic.severity() == DiagnosticSeverity.WARN) {
                assembly.warnings.add(new ImportRowWarning(record.sourceRowNumber(), input.artifact(),
                        VIEW_FALLBACK));
            } else {
                assembly.issues.add(issue(record, input.artifact(), DERIVATION_FAILED));
            }
        });
        result.value().forEach(candidate -> mergeCandidate(record, candidate, assembly));
    }

    private void mergeCandidate(ImportDelimitedRecord record, RoutedArtifactCandidate candidate,
                                RowAssembly assembly) {
        Set<String> targets = outputTargets.get(candidate.artifact());
        if (targets == null) {
            throw new IllegalStateException("Route produced unauthorized import artifact: "
                    + candidate.artifact());
        }
        assembly.preparedArtifacts.add(candidate.artifact());
        Map<String, String> values = assembly.outputs.computeIfAbsent(candidate.artifact(),
                ignored -> new LinkedHashMap<>());
        for (String target : targets) {
            if (!candidate.row().template().values().containsKey(target)) {
                throw new IllegalStateException("Route omitted bound import output: " + target);
            }
            String value = candidate.row().template().value(target);
            // A provider/gate with no value contributes nothing to this input.
            // Route-owned fields are replaced only after all inputs are assembled.
            if (value == null) {
                continue;
            }
            if (values.containsKey(target) && !Objects.equals(values.get(target), value)) {
                assembly.issues.add(issue(record, candidate.artifact(), COMPOUND_CONFLICT));
            } else {
                values.put(target, value);
            }
        }
    }

    private static void requirePrimaryOutput(ImportDelimitedRecord record, ImportLogicalRow admitted,
                                             RowAssembly assembly) {
        for (ImportArtifactBranch branch : admitted.branches()) {
            if (branch.role() == ImportArtifactRole.PRIMARY
                    && !assembly.preparedArtifacts.contains(branch.artifactName())) {
                assembly.issues.add(issue(record, branch.artifactName(), UNROUTABLE));
            }
        }
    }

    private ImportLogicalRow assembledRow(CompiledDataframeImportContract contract,
                                          ImportDelimitedRecord record, ImportLogicalRow admitted,
                                          RowAssembly assembly) {
        List<ImportArtifactBranch> branches = new ArrayList<>(admitted.branches().size());
        for (ImportArtifactBranch branch : admitted.branches()) {
            Map<String, ImportCell> cells = new LinkedHashMap<>(branch.cells());
            Map<String, ImportMergePolicy> policies = new LinkedHashMap<>(branch.mergePolicies());
            DataframeImportCatalogDraft.Artifact artifact = artifact(contract, branch.artifactName());
            Map<String, String> produced = assembly.outputs.getOrDefault(branch.artifactName(), Map.of());
            if (!produced.isEmpty()) {
                for (String target : outputTargets.getOrDefault(branch.artifactName(), Set.of())) {
                    ImportCell admittedCell = Objects.requireNonNull(cells.get(target),
                            "admitted route output " + branch.artifactName() + "." + target);
                    if (admittedCell.presence() == ImportCell.Presence.VALUE
                            && !produced.containsKey(target)) {
                        cells.put(target, ImportCell.nullValue());
                    }
                }
            }
            produced.forEach((target, value) -> {
                cells.put(target, ImportCell.value(value));
                policies.putIfAbsent(target, ImportMergePolicyResolver.resolve(contract, artifact, target));
            });
            branches.add(new ImportArtifactBranch(branch.artifactName(), branch.role(), cells,
                    policies, branch.requestedSlot(), java.util.Optional.empty(), List.of()));
        }
        return new ImportLogicalRow(record.sourceRowNumber(), branches);
    }

    private static final class RowAssembly {
        private final List<ImportRowIssue> issues = new ArrayList<>();
        private final List<ImportRowWarning> warnings = new ArrayList<>();
        private final Map<String, Map<String, String>> outputs = new LinkedHashMap<>();
        private final Set<String> preparedArtifacts = new LinkedHashSet<>();
    }

    private void requireBinding(CompiledDataframeImportContract contract, ImportLogicalRow row) {
        if (!contract.definition().id().equals(contractId)) {
            throw new IllegalStateException("Processed import plan does not match pinned contract");
        }
        Set<String> artifacts = new LinkedHashSet<>();
        contract.definition().artifacts().forEach(artifact -> artifacts.add(artifact.name()));
        if (!artifacts.containsAll(outputTargets.keySet())) {
            throw new IllegalStateException("Processed route targets exceed contract authority");
        }
        outputTargets.forEach((name, targets) -> {
            Set<String> authorized = new LinkedHashSet<>();
            artifact(contract, name).columns().forEach(column -> authorized.add(column.target()));
            if (!authorized.containsAll(targets)) {
                throw new IllegalStateException("Processed output fields exceed contract authority: " + name);
            }
        });
        for (Input input : inputs) {
            if (!artifacts.contains(input.artifact()) || !branch(row, input.artifact()).cells()
                    .containsKey(input.target())) {
                throw new IllegalStateException("Processed input is not admitted by contract");
            }
        }
        contract.definition().artifacts().forEach(artifact -> {
            if (artifact.sourceLabelTarget() != null && outputTargets
                    .getOrDefault(artifact.name(), Set.of()).contains(artifact.sourceLabelTarget())) {
                throw new IllegalStateException("Route cannot replace import source authority");
            }
        });
    }

    private static ImportArtifactBranch branch(ImportLogicalRow row, String artifact) {
        return row.branches().stream().filter(candidate -> candidate.artifactName().equals(artifact))
                .findFirst().orElseThrow(() -> new IllegalStateException("No admitted import branch: " + artifact));
    }

    private static DataframeImportCatalogDraft.Artifact artifact(
            CompiledDataframeImportContract contract, String name) {
        return contract.definition().artifacts().stream().filter(candidate -> candidate.name().equals(name))
                .findFirst().orElseThrow();
    }

    private static String sourceLabel(CompiledDataframeImportContract contract,
                                      ImportArtifactBranch branch) {
        String target = artifact(contract, branch.artifactName()).sourceLabelTarget();
        ImportCell cell = target == null ? null : branch.cells().get(target);
        return cell != null && cell.presence() == ImportCell.Presence.VALUE ? cell.value() : null;
    }

    private static ImportRowIssue issue(ImportDelimitedRecord record, String artifact, String code) {
        return new ImportRowIssue(record.sourceRowNumber(), artifact, code);
    }
}
