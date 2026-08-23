package com.iocextractor.application.dataframeimport.contract;

import com.iocextractor.application.dataframeimport.model.ImportArtifactRole;
import com.iocextractor.application.dataframeimport.model.ImportCatalogFingerprint;
import com.iocextractor.application.dataframeimport.model.ImportContractFingerprint;
import com.iocextractor.application.dataframeimport.model.ImportContractId;
import com.iocextractor.application.dataframeimport.model.ImportFormulaPolicy;
import com.iocextractor.application.dataframeimport.model.ImportMergePolicy;
import com.iocextractor.application.dataframeimport.model.ImportRoutingPolicy;
import com.iocextractor.application.dataframeimport.model.ImportSourceId;
import com.iocextractor.application.dataframeimport.model.ImportSourceTransport;

import java.nio.charset.Charset;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Collect-all compiler for declarative dataframe-import source contracts.
 * Validation is deterministic and does not inspect files, databases or adapters.
 */
public final class DataframeImportCatalogCompiler {

    /**
     * Validates all catalog entries and creates stable behavior fingerprints.
     *
     * @param draft untrusted configuration draft
     * @param environment names owned by artifact, transform and endpoint registries
     * @return all violations or one executable immutable catalog
     */
    public DataframeImportCatalogCompilation compile(DataframeImportCatalogDraft draft,
                                                     DataframeImportCatalogEnvironment environment) {
        List<ImportContractViolation> violations = new ArrayList<>();
        if (draft == null) {
            violations.add(violation("", "catalog must be configured"));
            return invalid(violations);
        }
        if (environment == null || environment.artifacts() == null
                || environment.transforms() == null || environment.endpoints() == null) {
            violations.add(violation("", "catalog reference environment is incomplete"));
            return invalid(violations);
        }

        Map<String, DataframeImportCatalogDraft.AuthorityProfile> authorities = authorities(
                draft, environment, violations);
        Map<String, DataframeImportCatalogDraft.Contract> contracts = contracts(draft, environment, violations);
        Map<String, DataframeImportCatalogDraft.Source> sources = sources(
                draft, environment, authorities, contracts, violations);

        if (draft.enabled()) {
            requireNotEmpty(draft.sources(), "sources", "at least one source is required when import is enabled", violations);
            requireNotEmpty(draft.authorityProfiles(), "authority-profiles",
                    "at least one authority profile is required when import is enabled", violations);
            requireNotEmpty(draft.contracts(), "contracts",
                    "at least one contract is required when import is enabled", violations);
        }
        if (!violations.isEmpty()) {
            return invalid(violations);
        }

        Map<ImportContractId, CompiledDataframeImportContract> compiledContracts = new LinkedHashMap<>();
        contracts.values().stream()
                .sorted(Comparator.comparing(DataframeImportCatalogDraft.Contract::id))
                .forEach(contract -> {
                    ImportContractId id = new ImportContractId(contract.id());
                    compiledContracts.put(id, new CompiledDataframeImportContract(
                            id, contract.version(), contract,
                            new ImportContractFingerprint(sha256(contractDescriptor(contract)))));
                });
        Map<ImportSourceId, DataframeImportCatalogDraft.Source> compiledSources = new LinkedHashMap<>();
        sources.values().stream()
                .sorted(Comparator.comparing(DataframeImportCatalogDraft.Source::id))
                .forEach(source -> compiledSources.put(new ImportSourceId(source.id()), source));
        Map<String, DataframeImportCatalogDraft.AuthorityProfile> compiledAuthorities = new LinkedHashMap<>();
        authorities.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> compiledAuthorities.put(entry.getKey(), entry.getValue()));

        String descriptor = catalogDescriptor(draft.enabled(), compiledSources, compiledAuthorities, compiledContracts);
        DataframeImportCatalog catalog = new DataframeImportCatalog(
                draft.enabled(), compiledSources, compiledAuthorities, compiledContracts,
                new ImportCatalogFingerprint(sha256(descriptor)));
        return new DataframeImportCatalogCompilation(Optional.of(catalog), List.of());
    }

    private Map<String, DataframeImportCatalogDraft.AuthorityProfile> authorities(
            DataframeImportCatalogDraft draft,
            DataframeImportCatalogEnvironment environment,
            List<ImportContractViolation> violations) {
        Map<String, DataframeImportCatalogDraft.AuthorityProfile> result = new LinkedHashMap<>();
        if (draft.authorityProfiles() == null) {
            violations.add(violation("authority-profiles", "authority profile list must not be null"));
            return result;
        }
        for (int i = 0; i < draft.authorityProfiles().size(); i++) {
            DataframeImportCatalogDraft.AuthorityProfile authority = draft.authorityProfiles().get(i);
            String path = "authority-profiles[%d]".formatted(i);
            if (authority == null) {
                violations.add(violation(path, "authority profile must be an object"));
                continue;
            }
            putUnique(result, authority.id(), authority, path + ".id", "authority profile", violations);
            requireNotEmpty(authority.artifacts(), path + ".artifacts",
                    "authority artifact allowlist must not be empty", violations);
            rejectBlankOrDuplicate(authority.artifacts(), path + ".artifacts", "artifact", violations);
            if (authority.artifacts() != null) {
                for (int artifactIndex = 0; artifactIndex < authority.artifacts().size(); artifactIndex++) {
                    String artifact = authority.artifacts().get(artifactIndex);
                    if (hasText(artifact) && !environment.artifacts().containsKey(artifact)) {
                        violations.add(violation(path + ".artifacts[%d]".formatted(artifactIndex),
                                "authority artifact must reference a configured canonical schema"));
                    }
                }
            }
            require(authority.maximumMergePolicy() != null, path + ".maximum-merge-policy",
                    "maximum merge policy is required", violations);
        }
        return result;
    }

    private Map<String, DataframeImportCatalogDraft.Contract> contracts(
            DataframeImportCatalogDraft draft,
            DataframeImportCatalogEnvironment environment,
            List<ImportContractViolation> violations) {
        Map<String, DataframeImportCatalogDraft.Contract> result = new LinkedHashMap<>();
        if (draft.contracts() == null) {
            violations.add(violation("contracts", "contract list must not be null"));
            return result;
        }
        for (int i = 0; i < draft.contracts().size(); i++) {
            DataframeImportCatalogDraft.Contract contract = draft.contracts().get(i);
            String path = "contracts[%d]".formatted(i);
            if (contract == null) {
                violations.add(violation(path, "contract must be an object"));
                continue;
            }
            putUnique(result, contract.id(), contract, path + ".id", "contract", violations);
            require(contract.version() > 0, path + ".version", "contract version must be positive", violations);
            validateCharset(contract.charset(), path + ".charset", violations);
            validateDialect(contract.dialect(), path + ".dialect", violations);
            Set<String> recognized = validateRecognition(contract.recognition(), path + ".recognition", violations);
            require(contract.mode() != null, path + ".mode", "processing mode is required", violations);
            require(contract.routing() != null, path + ".routing", "routing policy is required", violations);
            require(contract.rowFailurePolicy() != null, path + ".row-failure-policy",
                    "row failure policy is required", violations);
            require(contract.duplicatePolicy() != null, path + ".duplicate-policy",
                    "duplicate policy is required", violations);
            require(contract.formulaPolicy() != null, path + ".formula-policy",
                    "formula policy is required", violations);
            require(contract.mergeDefault() != null, path + ".merge-default",
                    "default merge policy is required", violations);
            DataframeImportCatalogDraft.Artifact primary = validateArtifacts(
                    contract, path, recognized, environment, violations);
            validateRequestedSlot(contract.requestedSlot(), primary, recognized, environment, path, violations);
        }
        return result;
    }

    private Map<String, DataframeImportCatalogDraft.Source> sources(
            DataframeImportCatalogDraft draft,
            DataframeImportCatalogEnvironment environment,
            Map<String, DataframeImportCatalogDraft.AuthorityProfile> authorities,
            Map<String, DataframeImportCatalogDraft.Contract> contracts,
            List<ImportContractViolation> violations) {
        Map<String, DataframeImportCatalogDraft.Source> result = new LinkedHashMap<>();
        if (draft.sources() == null) {
            violations.add(violation("sources", "source list must not be null"));
            return result;
        }
        for (int i = 0; i < draft.sources().size(); i++) {
            DataframeImportCatalogDraft.Source source = draft.sources().get(i);
            String path = "sources[%d]".formatted(i);
            if (source == null) {
                violations.add(violation(path, "source must be an object"));
                continue;
            }
            putUnique(result, source.id(), source, path + ".id", "source", violations);
            require(source.transport() != null, path + ".transport", "source transport is required", violations);
            requireText(source.location(), path + ".location", "source location is required", violations);
            validateEndpoint(source, environment, path, violations);
            requireNotEmpty(source.contracts(), path + ".contracts",
                    "source contract allowlist must not be empty", violations);
            rejectBlankOrDuplicate(source.contracts(), path + ".contracts", "contract reference", violations);
            DataframeImportCatalogDraft.AuthorityProfile authority = authorities.get(source.authority());
            if (!hasText(source.authority()) || authority == null) {
                violations.add(violation(path + ".authority", "source must reference a configured authority profile"));
            }
            if (source.contracts() != null) {
                for (int contractIndex = 0; contractIndex < source.contracts().size(); contractIndex++) {
                    String contractId = source.contracts().get(contractIndex);
                    DataframeImportCatalogDraft.Contract contract = contracts.get(contractId);
                    if (!hasText(contractId) || contract == null) {
                        violations.add(violation(path + ".contracts[%d]".formatted(contractIndex),
                                "source must reference a configured contract"));
                    } else if (authority != null) {
                        validateAuthority(source, authority, contract, path, contractIndex, violations);
                    }
                }
            }
        }
        return result;
    }

    private void validateEndpoint(DataframeImportCatalogDraft.Source source,
                                  DataframeImportCatalogEnvironment environment,
                                  String path,
                                  List<ImportContractViolation> violations) {
        if (source.transport() == ImportSourceTransport.SMB) {
            if (!hasText(source.endpoint()) || !environment.endpoints().contains(source.endpoint())) {
                violations.add(violation(path + ".endpoint", "SMB source must reference a configured sync endpoint"));
            }
        } else if (source.transport() == ImportSourceTransport.LOCAL && hasText(source.endpoint())) {
            violations.add(violation(path + ".endpoint", "local source must not configure a remote endpoint"));
        }
    }

    private void validateAuthority(DataframeImportCatalogDraft.Source source,
                                   DataframeImportCatalogDraft.AuthorityProfile authority,
                                   DataframeImportCatalogDraft.Contract contract,
                                   String sourcePath,
                                   int contractIndex,
                                   List<ImportContractViolation> violations) {
        String path = sourcePath + ".contracts[%d]".formatted(contractIndex);
        if (contract.routing() == ImportRoutingPolicy.RELATED_ARTIFACTS && !authority.allowRelatedRouting()) {
            violations.add(violation(path, "source authority does not permit related-artifact routing"));
        }
        if (contract.formulaPolicy() == ImportFormulaPolicy.MACHINE_ONLY_PRESERVE
                && !authority.allowMachineOnlyFormulaPreserve()) {
            violations.add(violation(path, "source authority does not permit machine-only formula preservation"));
        }
        if (contract.mergeDefault() != null
                && !contract.mergeDefault().isAllowedBy(authority.maximumMergePolicy())) {
            violations.add(violation(path, "contract default merge policy exceeds source authority"));
        }
        if (contract.artifacts() == null) {
            return;
        }
        Set<String> allowedArtifacts = authority.artifacts() == null
                ? Set.of() : new HashSet<>(authority.artifacts());
        for (DataframeImportCatalogDraft.Artifact artifact : contract.artifacts()) {
            if (artifact == null) {
                continue;
            }
            if (!allowedArtifacts.contains(artifact.name())) {
                violations.add(violation(path, "contract artifact is outside the source authority allowlist"));
            }
            validateMergeCeiling(artifact.mergeDefault(), authority.maximumMergePolicy(), path, violations);
            if (artifact.columns() != null) {
                for (DataframeImportCatalogDraft.Column column : artifact.columns()) {
                    if (column != null) {
                        validateMergeCeiling(column.mergePolicy(), authority.maximumMergePolicy(), path, violations);
                    }
                }
            }
        }
    }

    private void validateMergeCeiling(ImportMergePolicy policy,
                                      ImportMergePolicy ceiling,
                                      String path,
                                      List<ImportContractViolation> violations) {
        if (policy != null && !policy.isAllowedBy(ceiling)) {
            violations.add(violation(path, "artifact or column merge override exceeds source authority"));
        }
    }

    private void validateCharset(String charset, String path, List<ImportContractViolation> violations) {
        if (!hasText(charset)) {
            violations.add(violation(path, "declared charset is required"));
            return;
        }
        try {
            if (!Charset.isSupported(charset)) {
                violations.add(violation(path, "declared charset is not supported by this runtime"));
            }
        } catch (IllegalCharsetNameException exception) {
            violations.add(violation(path, "declared charset name is invalid"));
        }
    }

    private void validateDialect(DataframeImportCatalogDraft.Dialect dialect,
                                 String path,
                                 List<ImportContractViolation> violations) {
        if (dialect == null) {
            violations.add(violation(path, "CSV dialect is required"));
            return;
        }
        requireSingleCharacter(dialect.delimiter(), path + ".delimiter", "delimiter", violations);
        requireSingleCharacter(dialect.quote(), path + ".quote", "quote", violations);
        if (hasText(dialect.delimiter()) && dialect.delimiter().equals(dialect.quote())) {
            violations.add(violation(path + ".quote", "quote must differ from delimiter"));
        }
        require(dialect.recordSeparator() != null, path + ".record-separator",
                "record separator policy is required", violations);
        require(dialect.headerRequired(), path + ".header-required",
                "V1 requires an explicit header", violations);
        if (dialect.nullLiterals() == null) {
            violations.add(violation(path + ".null-literals", "null literal list must not be null"));
        } else {
            rejectDuplicate(dialect.nullLiterals(), path + ".null-literals", "null literal", true, violations);
        }
    }

    private Set<String> validateRecognition(DataframeImportCatalogDraft.Recognition recognition,
                                            String path,
                                            List<ImportContractViolation> violations) {
        Set<String> canonical = new LinkedHashSet<>();
        if (recognition == null) {
            violations.add(violation(path, "recognition signature is required"));
            return canonical;
        }
        requireNotEmpty(recognition.requiredColumns(), path + ".required-columns",
                "at least one required column is needed for recognition", violations);
        addHeaders(recognition.requiredColumns(), path + ".required-columns", canonical, violations);
        addHeaders(recognition.optionalColumns(), path + ".optional-columns", canonical, violations);
        addHeaders(recognition.ignoredColumns(), path + ".ignored-columns", canonical, violations);
        if (recognition.aliases() == null) {
            violations.add(violation(path + ".aliases", "header alias map must not be null"));
        } else {
            Set<String> targets = new HashSet<>();
            recognition.aliases().entrySet().stream()
                    .sorted(Map.Entry.comparingByKey(Comparator.nullsFirst(String::compareTo)))
                    .forEach(entry -> {
                if (!hasText(entry.getKey()) || !hasText(entry.getValue())) {
                    violations.add(violation(path + ".aliases", "header aliases must have non-blank source and target"));
                } else if (!canonical.contains(entry.getValue())) {
                    violations.add(violation(path + ".aliases", "header alias target must be a declared canonical column"));
                } else if (!targets.add(entry.getValue())) {
                    violations.add(violation(path + ".aliases", "multiple aliases must not collapse onto one canonical header"));
                }
                    });
        }
        if (recognition.ignoredColumns() != null) {
            canonical.removeAll(recognition.ignoredColumns());
        }
        return canonical;
    }

    private void addHeaders(List<String> headers,
                            String path,
                            Set<String> canonical,
                            List<ImportContractViolation> violations) {
        if (headers == null) {
            violations.add(violation(path, "column list must not be null"));
            return;
        }
        for (int i = 0; i < headers.size(); i++) {
            String header = headers.get(i);
            if (!hasText(header)) {
                violations.add(violation(path + "[%d]".formatted(i), "column name must not be blank"));
            } else if (!canonical.add(header)) {
                violations.add(violation(path + "[%d]".formatted(i),
                        "canonical column must occur in exactly one recognition set"));
            }
        }
    }

    private DataframeImportCatalogDraft.Artifact validateArtifacts(
            DataframeImportCatalogDraft.Contract contract,
            String contractPath,
            Set<String> recognized,
            DataframeImportCatalogEnvironment environment,
            List<ImportContractViolation> violations) {
        if (contract.artifacts() == null || contract.artifacts().isEmpty()) {
            violations.add(violation(contractPath + ".artifacts", "at least one artifact mapping is required"));
            return null;
        }
        DataframeImportCatalogDraft.Artifact primary = null;
        Set<String> artifactNames = new HashSet<>();
        for (int i = 0; i < contract.artifacts().size(); i++) {
            DataframeImportCatalogDraft.Artifact artifact = contract.artifacts().get(i);
            String path = contractPath + ".artifacts[%d]".formatted(i);
            if (artifact == null) {
                violations.add(violation(path, "artifact mapping must be an object"));
                continue;
            }
            if (!hasText(artifact.name()) || !artifactNames.add(artifact.name())) {
                violations.add(violation(path + ".name", "artifact mapping name must be non-blank and unique"));
            }
            if (artifact.role() == ImportArtifactRole.PRIMARY) {
                if (primary == null) {
                    primary = artifact;
                } else {
                    violations.add(violation(path + ".role", "exactly one primary artifact is allowed"));
                }
            } else if (artifact.role() == null) {
                violations.add(violation(path + ".role", "artifact role is required"));
            }
            if (artifact.role() == ImportArtifactRole.RELATED
                    && contract.routing() == ImportRoutingPolicy.TARGET_ONLY) {
                violations.add(violation(path + ".role", "target-only routing must not declare related artifacts"));
            }
            DataframeImportCatalogEnvironment.ArtifactSchema schema = environment.artifacts().get(artifact.name());
            if (schema == null) {
                violations.add(violation(path + ".name", "artifact must reference a configured canonical schema"));
            }
            if (!hasText(artifact.recordKey()) || schema == null || !artifact.recordKey().equals(schema.recordKey())) {
                violations.add(violation(path + ".record-key",
                        "record key must reference the artifact's active identity definition"));
            }
            validateMatchKeys(artifact, schema, path, violations);
            validateColumns(artifact, schema, recognized, environment.transforms(), path, violations);
        }
        if (primary == null) {
            violations.add(violation(contractPath + ".artifacts", "exactly one primary artifact is required"));
        }
        return primary;
    }

    private void validateMatchKeys(DataframeImportCatalogDraft.Artifact artifact,
                                   DataframeImportCatalogEnvironment.ArtifactSchema schema,
                                   String path,
                                   List<ImportContractViolation> violations) {
        requireNotEmpty(artifact.matchKeys(), path + ".match-keys",
                "at least one match-key definition is required", violations);
        rejectBlankOrDuplicate(artifact.matchKeys(), path + ".match-keys", "match-key reference", violations);
        if (schema == null || artifact.matchKeys() == null || schema.matchKeys() == null) {
            return;
        }
        for (int i = 0; i < artifact.matchKeys().size(); i++) {
            String matchKey = artifact.matchKeys().get(i);
            if (hasText(matchKey) && !schema.matchKeys().contains(matchKey)) {
                violations.add(violation(path + ".match-keys[%d]".formatted(i),
                        "match key must reference an artifact identity definition"));
            }
        }
    }

    private void validateColumns(DataframeImportCatalogDraft.Artifact artifact,
                                 DataframeImportCatalogEnvironment.ArtifactSchema schema,
                                 Set<String> recognized,
                                 Set<String> transforms,
                                 String path,
                                 List<ImportContractViolation> violations) {
        requireNotEmpty(artifact.columns(), path + ".columns", "at least one column mapping is required", violations);
        if (artifact.columns() == null) {
            return;
        }
        Set<String> targets = new HashSet<>();
        for (int i = 0; i < artifact.columns().size(); i++) {
            DataframeImportCatalogDraft.Column column = artifact.columns().get(i);
            String columnPath = path + ".columns[%d]".formatted(i);
            if (column == null) {
                violations.add(violation(columnPath, "column mapping must be an object"));
                continue;
            }
            if (!hasText(column.target()) || !targets.add(column.target())) {
                violations.add(violation(columnPath + ".target", "target column must be non-blank and unique"));
            } else if (schema != null && schema.columns() != null && !schema.columns().contains(column.target())) {
                violations.add(violation(columnPath + ".target", "target must reference a public artifact column"));
            }
            if (!hasText(column.source()) || !recognized.contains(column.source())) {
                violations.add(violation(columnPath + ".source",
                        "source must reference a required or optional canonical header"));
            }
            validateTransforms(column.transforms(), transforms, columnPath + ".transforms", violations);
        }
    }

    private void validateTransforms(List<String> specs,
                                    Set<String> transforms,
                                    String path,
                                    List<ImportContractViolation> violations) {
        if (specs == null) {
            violations.add(violation(path, "transform list must not be null"));
            return;
        }
        for (int i = 0; i < specs.size(); i++) {
            String spec = specs.get(i);
            String name = transformName(spec);
            if (!hasText(name) || !transforms.contains(name)) {
                violations.add(violation(path + "[%d]".formatted(i),
                        "transform must reference a registered transform name"));
            }
        }
    }

    private void validateRequestedSlot(DataframeImportCatalogDraft.RequestedSlot requested,
                                       DataframeImportCatalogDraft.Artifact primary,
                                       Set<String> recognized,
                                       DataframeImportCatalogEnvironment environment,
                                       String contractPath,
                                       List<ImportContractViolation> violations) {
        if (requested == null) {
            return;
        }
        String path = contractPath + ".requested-slot";
        if (!hasText(requested.sourceColumn()) || !recognized.contains(requested.sourceColumn())) {
            violations.add(violation(path + ".source-column",
                    "requested slot source must be a mapped recognition column"));
        }
        requireText(requested.profile(), path + ".profile", "requested slot profile is required", violations);
        require(requested.existingRecordPolicy() != null, path + ".existing-record-policy",
                "existing-record slot policy is required", violations);
        if (primary == null) {
            return;
        }
        DataframeImportCatalogEnvironment.ArtifactSchema schema = environment.artifacts().get(primary.name());
        if (schema == null) {
            return;
        }
        if (!schema.hasExternalId()) {
            violations.add(violation(path, "primary artifact has no external export slot"));
        }
        if (schema.slotProfiles() == null || !schema.slotProfiles().contains(requested.profile())) {
            violations.add(violation(path + ".profile",
                    "profile must include the primary artifact in stable-slot export"));
        }
    }

    private String contractDescriptor(DataframeImportCatalogDraft.Contract contract) {
        FingerprintBuilder builder = new FingerprintBuilder("dataframe-import-contract:v1");
        builder.value("id", contract.id()).number("version", contract.version()).value("charset", contract.charset());
        DataframeImportCatalogDraft.Dialect dialect = contract.dialect();
        builder.value("delimiter", dialect.delimiter()).value("quote", dialect.quote())
                .token("record-separator", dialect.recordSeparator()).flag("header-required", dialect.headerRequired())
                .sorted("null-literals", dialect.nullLiterals());
        DataframeImportCatalogDraft.Recognition recognition = contract.recognition();
        builder.sorted("required", recognition.requiredColumns()).sorted("optional", recognition.optionalColumns())
                .sorted("ignored", recognition.ignoredColumns()).sortedMap("aliases", recognition.aliases());
        builder.token("mode", contract.mode()).token("routing", contract.routing())
                .token("row-failure", contract.rowFailurePolicy()).token("duplicate", contract.duplicatePolicy())
                .flag("renew-unchanged", contract.renewUnchanged()).token("formula", contract.formulaPolicy())
                .token("merge-default", contract.mergeDefault());
        contract.artifacts().stream()
                .sorted(Comparator.comparing(DataframeImportCatalogDraft.Artifact::name)
                        .thenComparing(artifact -> artifact.role().token()))
                .forEach(artifact -> {
                    builder.value("artifact", artifact.name()).token("role", artifact.role())
                            .value("record-key", artifact.recordKey())
                            .sorted("match-keys", artifact.matchKeys()).token("artifact-merge", artifact.mergeDefault());
                    artifact.columns().stream().sorted(Comparator.comparing(DataframeImportCatalogDraft.Column::target))
                            .forEach(column -> builder.value("target", column.target()).value("source", column.source())
                                    .ordered("transforms", column.transforms()).token("column-merge", column.mergePolicy()));
                });
        DataframeImportCatalogDraft.RequestedSlot slot = contract.requestedSlot();
        if (slot == null) {
            builder.value("requested-slot", null);
        } else {
            builder.value("slot-source", slot.sourceColumn()).value("slot-profile", slot.profile())
                    .token("slot-existing", slot.existingRecordPolicy());
        }
        return builder.build();
    }

    private String catalogDescriptor(
            boolean enabled,
            Map<ImportSourceId, DataframeImportCatalogDraft.Source> sources,
            Map<String, DataframeImportCatalogDraft.AuthorityProfile> authorities,
            Map<ImportContractId, CompiledDataframeImportContract> contracts) {
        FingerprintBuilder builder = new FingerprintBuilder("dataframe-import-catalog:v1").flag("enabled", enabled);
        sources.entrySet().stream().sorted(Comparator.comparing(entry -> entry.getKey().value())).forEach(entry -> {
            DataframeImportCatalogDraft.Source source = entry.getValue();
            builder.value("source", source.id()).token("transport", source.transport())
                    .value("location", source.location()).value("endpoint", source.endpoint())
                    .sorted("contracts", source.contracts()).value("authority", source.authority());
        });
        authorities.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            DataframeImportCatalogDraft.AuthorityProfile authority = entry.getValue();
            builder.value("authority", authority.id()).sorted("artifacts", authority.artifacts())
                    .token("maximum-merge", authority.maximumMergePolicy())
                    .flag("related", authority.allowRelatedRouting())
                    .flag("machine-formula", authority.allowMachineOnlyFormulaPreserve());
        });
        contracts.entrySet().stream().sorted(Comparator.comparing(entry -> entry.getKey().value()))
                .forEach(entry -> builder.value("contract", entry.getKey().value())
                        .value("contract-fingerprint", entry.getValue().fingerprint().value()));
        return builder.build();
    }

    private <T> void putUnique(Map<String, T> values,
                           String id,
                           T value,
                           String path,
                           String label,
                           List<ImportContractViolation> violations) {
        if (!hasText(id)) {
            violations.add(violation(path, label + " ID must not be blank"));
            return;
        }
        if (values.putIfAbsent(id, value) != null) {
            violations.add(violation(path, label + " ID must be unique"));
        }
    }

    private void requireSingleCharacter(String value,
                                        String path,
                                        String label,
                                        List<ImportContractViolation> violations) {
        if (value == null || value.codePointCount(0, value.length()) != 1) {
            violations.add(violation(path, label + " must contain exactly one Unicode code point"));
        }
    }

    private void requireText(String value,
                             String path,
                             String message,
                             List<ImportContractViolation> violations) {
        require(hasText(value), path, message, violations);
    }

    private void requireNotEmpty(List<?> values,
                                 String path,
                                 String message,
                                 List<ImportContractViolation> violations) {
        require(values != null && !values.isEmpty(), path, message, violations);
    }

    private void require(boolean condition,
                         String path,
                         String message,
                         List<ImportContractViolation> violations) {
        if (!condition) {
            violations.add(violation(path, message));
        }
    }

    private void rejectBlankOrDuplicate(List<String> values,
                                        String path,
                                        String label,
                                        List<ImportContractViolation> violations) {
        rejectDuplicate(values, path, label, true, violations);
    }

    private void rejectDuplicate(List<String> values,
                                 String path,
                                 String label,
                                 boolean rejectBlank,
                                 List<ImportContractViolation> violations) {
        if (values == null) {
            return;
        }
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < values.size(); i++) {
            String value = values.get(i);
            if (rejectBlank && !hasText(value)) {
                violations.add(violation(path + "[%d]".formatted(i), label + " must not be blank"));
            } else if (!seen.add(value)) {
                violations.add(violation(path + "[%d]".formatted(i), label + " must be unique"));
            }
        }
    }

    private String transformName(String spec) {
        if (spec == null) {
            return null;
        }
        int separator = spec.indexOf(':');
        return separator < 0 ? spec : spec.substring(0, separator);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private ImportContractViolation violation(String path, String message) {
        return new ImportContractViolation(path.isBlank() ? "dataframe-import" : path, message);
    }

    private DataframeImportCatalogCompilation invalid(List<ImportContractViolation> violations) {
        return new DataframeImportCatalogCompilation(Optional.empty(), violations);
    }

    private static String sha256(String descriptor) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(descriptor.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static final class FingerprintBuilder {
        private final StringBuilder descriptor = new StringBuilder();

        private FingerprintBuilder(String format) {
            append("format", format);
        }

        private FingerprintBuilder value(String name, String value) {
            append(name, value);
            return this;
        }

        private FingerprintBuilder number(String name, long value) {
            return value(name, Long.toString(value));
        }

        private FingerprintBuilder flag(String name, boolean value) {
            return value(name, Boolean.toString(value));
        }

        private FingerprintBuilder token(String name, com.iocextractor.application.dataframeimport.model.ImportPolicyToken value) {
            return value(name, value == null ? null : value.token());
        }

        private FingerprintBuilder sorted(String name, List<String> values) {
            if (values == null) {
                append(name, null);
                return this;
            }
            values.stream().sorted(Comparator.nullsFirst(String::compareTo))
                    .forEach(value -> append(name, value));
            return this;
        }

        private FingerprintBuilder ordered(String name, List<String> values) {
            if (values == null) {
                append(name, null);
                return this;
            }
            values.forEach(value -> append(name, value));
            return this;
        }

        private FingerprintBuilder sortedMap(String name, Map<String, String> values) {
            if (values == null) {
                append(name, null);
                return this;
            }
            values.entrySet().stream().sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> append(name + ".key", entry.getKey()).append(name + ".value", entry.getValue()));
            return this;
        }

        private FingerprintBuilder append(String name, String value) {
            descriptor.append(name.length()).append(':').append(name).append('=');
            if (value == null) {
                descriptor.append("-1:");
            } else {
                descriptor.append(value.length()).append(':').append(value);
            }
            descriptor.append(';');
            return this;
        }

        private String build() {
            return descriptor.toString();
        }
    }
}
