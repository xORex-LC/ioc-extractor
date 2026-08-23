package com.iocextractor.application.dataframeimport.contract;

import com.iocextractor.application.dataframeimport.model.ImportArtifactRole;
import com.iocextractor.application.dataframeimport.model.ImportDuplicatePolicy;
import com.iocextractor.application.dataframeimport.model.ImportExistingSlotPolicy;
import com.iocextractor.application.dataframeimport.model.ImportFormulaPolicy;
import com.iocextractor.application.dataframeimport.model.ImportMergePolicy;
import com.iocextractor.application.dataframeimport.model.ImportProcessingMode;
import com.iocextractor.application.dataframeimport.model.ImportRecordSeparator;
import com.iocextractor.application.dataframeimport.model.ImportRoutingPolicy;
import com.iocextractor.application.dataframeimport.model.ImportRowFailurePolicy;
import com.iocextractor.application.dataframeimport.model.ImportSourceTransport;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Framework-free, not-yet-trusted catalog shape supplied by the composition
 * root. The compiler validates all references before producing an executable
 * immutable catalog.
 *
 * @param enabled whether runtime import may be wired
 * @param sources configured trust/transport boundaries
 * @param authorityProfiles mutation ceilings referenced by sources
 * @param contracts supported structured dataframe contracts
 */
public record DataframeImportCatalogDraft(
        boolean enabled,
        List<Source> sources,
        List<AuthorityProfile> authorityProfiles,
        List<Contract> contracts) {

    /** Snapshots collection containers while preserving invalid null elements for collect-all validation. */
    public DataframeImportCatalogDraft {
        sources = snapshotList(sources);
        authorityProfiles = snapshotList(authorityProfiles);
        contracts = snapshotList(contracts);
    }

    /** Returns the immutable source snapshot. */
    @Override
    public List<Source> sources() {
        return readOnly(sources);
    }

    /** Returns the immutable authority-profile snapshot. */
    @Override
    public List<AuthorityProfile> authorityProfiles() {
        return readOnly(authorityProfiles);
    }

    /** Returns the immutable contract snapshot. */
    @Override
    public List<Contract> contracts() {
        return readOnly(contracts);
    }

    /**
     * One managed source and its contract/authority allowlist.
     *
     * @param id stable source ID
     * @param transport local or SMB transport family
     * @param location transport-relative source location
     * @param endpoint required SMB endpoint reference; absent for local
     * @param contracts allowed contract IDs
     * @param authority authority-profile reference
     */
    public record Source(String id,
                         ImportSourceTransport transport,
                         String location,
                         String endpoint,
                         List<String> contracts,
                         String authority) {
        /** Snapshots the contract allowlist. */
        public Source {
            contracts = snapshotList(contracts);
        }

        /** Returns the immutable contract allowlist. */
        @Override
        public List<String> contracts() {
            return readOnly(contracts);
        }
    }

    /**
     * Maximum mutation authority granted to one source trust boundary.
     *
     * @param id stable profile ID
     * @param artifacts allowed artifacts
     * @param maximumMergePolicy destructive merge ceiling
     * @param allowRelatedRouting whether contracts may fan out beyond the primary artifact
     * @param allowMachineOnlyFormulaPreserve whether exact dangerous text may be preserved
     */
    public record AuthorityProfile(String id,
                                   List<String> artifacts,
                                   ImportMergePolicy maximumMergePolicy,
                                   boolean allowRelatedRouting,
                                   boolean allowMachineOnlyFormulaPreserve) {
        /** Snapshots the artifact allowlist. */
        public AuthorityProfile {
            artifacts = snapshotList(artifacts);
        }

        /** Returns the immutable artifact allowlist. */
        @Override
        public List<String> artifacts() {
            return readOnly(artifacts);
        }
    }

    /**
     * One versioned recognition, mapping and policy contract.
     *
     * @param id stable contract ID
     * @param version positive operator-managed version
     * @param charset declared input charset
     * @param dialect CSV dialect
     * @param recognition exact structural recognition signature
     * @param mode as-is or processed mode
     * @param routing target-only or related-artifact routing
     * @param rowFailurePolicy partial/strict row failure behavior
     * @param duplicatePolicy within-delivery duplicate behavior
     * @param renewUnchanged whether accepted unchanged observations renew lifecycle validity
     * @param formulaPolicy spreadsheet-formula policy
     * @param mergeDefault default field merge policy
     * @param artifacts primary and optional related mappings
     * @param requestedSlot optional external-slot mapping
     */
    public record Contract(String id,
                           int version,
                           String charset,
                           Dialect dialect,
                           Recognition recognition,
                           ImportProcessingMode mode,
                           ImportRoutingPolicy routing,
                           ImportRowFailurePolicy rowFailurePolicy,
                           ImportDuplicatePolicy duplicatePolicy,
                           boolean renewUnchanged,
                           ImportFormulaPolicy formulaPolicy,
                           ImportMergePolicy mergeDefault,
                           List<Artifact> artifacts,
                           RequestedSlot requestedSlot) {
        /** Snapshots artifact mappings. */
        public Contract {
            artifacts = snapshotList(artifacts);
        }

        /** Returns the immutable artifact-mapping snapshot. */
        @Override
        public List<Artifact> artifacts() {
            return readOnly(artifacts);
        }
    }

    /**
     * Library-neutral strict CSV dialect.
     *
     * @param delimiter one-character delimiter
     * @param quote one-character quote
     * @param recordSeparator accepted record separator policy
     * @param headerRequired whether a header is mandatory
     * @param nullLiterals exact configured null literals after declared normalization
     */
    public record Dialect(String delimiter,
                          String quote,
                          ImportRecordSeparator recordSeparator,
                          boolean headerRequired,
                          List<String> nullLiterals) {
        /** Snapshots null literals. */
        public Dialect {
            nullLiterals = snapshotList(nullLiterals);
        }

        /** Returns the immutable null-literal snapshot. */
        @Override
        public List<String> nullLiterals() {
            return readOnly(nullLiterals);
        }
    }

    /**
     * Exact structural recognition signature independent of filename and column order.
     *
     * @param requiredColumns required canonical headers
     * @param optionalColumns allowed optional canonical headers
     * @param ignoredColumns allowed headers discarded before mapping
     * @param aliases external header to canonical-header mapping
     */
    public record Recognition(List<String> requiredColumns,
                              List<String> optionalColumns,
                              List<String> ignoredColumns,
                              Map<String, String> aliases) {
        /** Snapshots recognition collections. */
        public Recognition {
            requiredColumns = snapshotList(requiredColumns);
            optionalColumns = snapshotList(optionalColumns);
            ignoredColumns = snapshotList(ignoredColumns);
            aliases = snapshotMap(aliases);
        }

        /** Returns the immutable required-column snapshot. */
        @Override
        public List<String> requiredColumns() {
            return readOnly(requiredColumns);
        }

        /** Returns the immutable optional-column snapshot. */
        @Override
        public List<String> optionalColumns() {
            return readOnly(optionalColumns);
        }

        /** Returns the immutable ignored-column snapshot. */
        @Override
        public List<String> ignoredColumns() {
            return readOnly(ignoredColumns);
        }

        /** Returns the immutable alias snapshot. */
        @Override
        public Map<String, String> aliases() {
            return readOnly(aliases);
        }
    }

    /**
     * Mapping for one primary or related artifact branch.
     *
     * @param name configured canonical artifact name
     * @param role primary or related role
     * @param recordKey required versioned canonical row-key definition ID
     * @param matchKeys allowed artifact match-key definition IDs
     * @param mergeDefault optional artifact-level merge override
     * @param columns target-column mappings
     */
    public record Artifact(String name,
                           ImportArtifactRole role,
                           String recordKey,
                           List<String> matchKeys,
                           ImportMergePolicy mergeDefault,
                           List<Column> columns) {
        /** Snapshots match keys and column mappings. */
        public Artifact {
            matchKeys = snapshotList(matchKeys);
            columns = snapshotList(columns);
        }

        /** Returns the immutable match-key snapshot. */
        @Override
        public List<String> matchKeys() {
            return readOnly(matchKeys);
        }

        /** Returns the immutable column-mapping snapshot. */
        @Override
        public List<Column> columns() {
            return readOnly(columns);
        }
    }

    /**
     * Mapping for one target artifact column.
     *
     * @param target target public column
     * @param source canonical recognized source header
     * @param transforms ordered registered transform specifications
     * @param mergePolicy optional column-level merge override
     */
    public record Column(String target,
                         String source,
                         List<String> transforms,
                         ImportMergePolicy mergePolicy) {
        /** Snapshots ordered transforms. */
        public Column {
            transforms = snapshotList(transforms);
        }

        /** Returns the immutable ordered transform snapshot. */
        @Override
        public List<String> transforms() {
            return readOnly(transforms);
        }
    }

    /**
     * Optional requested external-slot mapping scoped to the primary artifact.
     *
     * @param sourceColumn recognized source header containing the requested positive slot
     * @param profile immutable export profile scope
     * @param existingRecordPolicy survivor mismatch behavior
     */
    public record RequestedSlot(String sourceColumn,
                                String profile,
                                ImportExistingSlotPolicy existingRecordPolicy) {
    }

    private static <T> List<T> snapshotList(List<T> source) {
        return source == null ? null : Collections.unmodifiableList(new ArrayList<>(source));
    }

    private static <T> List<T> readOnly(List<T> source) {
        return source == null ? null : Collections.unmodifiableList(source);
    }

    private static <K, V> Map<K, V> readOnly(Map<K, V> source) {
        return source == null ? null : Collections.unmodifiableMap(source);
    }

    private static <K, V> Map<K, V> snapshotMap(Map<K, V> source) {
        return source == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }
}
