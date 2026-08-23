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
 * @param endpoints configured transport endpoint names
 */
public record DataframeImportCatalogEnvironment(
        Map<String, ArtifactSchema> artifacts,
        Set<String> transforms,
        Set<String> endpoints) {

    /** Snapshots all registry containers. */
    public DataframeImportCatalogEnvironment {
        artifacts = artifacts == null ? null : Map.copyOf(new LinkedHashMap<>(artifacts));
        transforms = transforms == null ? null : Set.copyOf(new LinkedHashSet<>(transforms));
        endpoints = endpoints == null ? null : Set.copyOf(new LinkedHashSet<>(endpoints));
    }

    /**
     * Referenced capabilities of one configured canonical artifact.
     *
     * @param columns public artifact columns
     * @param recordKey active canonical row-key definition ID
     * @param matchKeys declared match-key definition IDs
     * @param slotProfiles immutable export profiles containing the artifact
     * @param hasExternalId whether the artifact exposes an external slot
     */
    public record ArtifactSchema(Set<String> columns,
                                 String recordKey,
                                 Set<String> matchKeys,
                                 Set<String> slotProfiles,
                                 boolean hasExternalId) {
        /** Snapshots schema sets. */
        public ArtifactSchema {
            columns = columns == null ? null : Set.copyOf(new LinkedHashSet<>(columns));
            matchKeys = matchKeys == null ? null : Set.copyOf(new LinkedHashSet<>(matchKeys));
            slotProfiles = slotProfiles == null ? null : Set.copyOf(new LinkedHashSet<>(slotProfiles));
        }
    }
}
