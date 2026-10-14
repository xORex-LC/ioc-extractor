package com.iocextractor.application.dataframeimport.contract;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Framework-free catalog of names owned by other configured capabilities.
 * Import contracts may only reference values present here.
 *
 * @param artifacts canonical artifact schemas and matching/export capabilities
 * @param transforms registered transform names
 * @param validators registered value-validation rule names
 * @param endpoints configured transport endpoint names
 * @param processingPolicyFingerprint effective ordinary-processing policy identity
 */
public record DataframeImportCatalogEnvironment(
        Map<String, ArtifactSchema> artifacts,
        Set<String> transforms,
        Set<String> validators,
        Set<String> endpoints,
        String processingPolicyFingerprint) {

    /** Snapshots all registry containers. */
    public DataframeImportCatalogEnvironment {
        artifacts = artifacts == null ? null : Map.copyOf(new LinkedHashMap<>(artifacts));
        transforms = transforms == null ? null : Set.copyOf(new LinkedHashSet<>(transforms));
        validators = validators == null ? null : Set.copyOf(new LinkedHashSet<>(validators));
        endpoints = endpoints == null ? null : Set.copyOf(new LinkedHashSet<>(endpoints));
    }

    /** Compatibility constructor for catalogs that do not declare value validation. */
    public DataframeImportCatalogEnvironment(Map<String, ArtifactSchema> artifacts,
                                             Set<String> transforms,
                                             Set<String> endpoints) {
        this(artifacts, transforms, Set.of(), endpoints, null);
    }

    /** Compatibility constructor for environments without a pinned processing policy. */
    public DataframeImportCatalogEnvironment(Map<String, ArtifactSchema> artifacts,
                                             Set<String> transforms,
                                             Set<String> validators,
                                             Set<String> endpoints) {
        this(artifacts, transforms, validators, endpoints, null);
    }

    /**
     * Referenced capabilities of one configured canonical artifact.
     *
     * @param columns public artifact columns
     * @param recordKey active canonical row-key definition ID
     * @param matchKeys declared match-key definition IDs
     * @param slotProfiles immutable export profiles containing the artifact
     * @param hasExternalId whether the artifact exposes an external slot
     * @param sourceLabelTargets output columns backed by ordinary source.label mapping
     */
    public record ArtifactSchema(Set<String> columns,
                                 String recordKey,
                                 Set<String> matchKeys,
                                 Set<String> slotProfiles,
                                 boolean hasExternalId,
                                 Set<String> sourceLabelTargets) {
        /** Snapshots schema sets. */
        public ArtifactSchema {
            columns = columns == null ? null : Set.copyOf(new LinkedHashSet<>(columns));
            matchKeys = matchKeys == null ? null : Set.copyOf(new LinkedHashSet<>(matchKeys));
            slotProfiles = slotProfiles == null ? null : Set.copyOf(new LinkedHashSet<>(slotProfiles));
            sourceLabelTargets = sourceLabelTargets == null
                    ? null : Set.copyOf(new LinkedHashSet<>(sourceLabelTargets));
        }

        /** Compatibility constructor for schemas without processed source bindings. */
        public ArtifactSchema(Set<String> columns, String recordKey, Set<String> matchKeys,
                              Set<String> slotProfiles, boolean hasExternalId) {
            this(columns, recordKey, matchKeys, slotProfiles, hasExternalId, Set.of());
        }
    }
}
