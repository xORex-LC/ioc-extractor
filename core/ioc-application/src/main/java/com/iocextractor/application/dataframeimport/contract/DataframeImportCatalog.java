package com.iocextractor.application.dataframeimport.contract;

import com.iocextractor.application.dataframeimport.model.ImportCatalogFingerprint;
import com.iocextractor.application.dataframeimport.model.ImportContractId;
import com.iocextractor.application.dataframeimport.model.ImportSourceId;

import java.util.Map;
import java.util.Objects;

/**
 * Validated immutable catalog generation activated for one application run.
 *
 * @param enabled whether runtime import may be wired
 * @param sources validated sources indexed by identity
 * @param authorityProfiles validated authority profiles indexed by identity
 * @param contracts compiled contracts indexed by identity
 * @param fingerprint complete catalog fingerprint
 */
public record DataframeImportCatalog(
        boolean enabled,
        Map<ImportSourceId, DataframeImportCatalogDraft.Source> sources,
        Map<String, DataframeImportCatalogDraft.AuthorityProfile> authorityProfiles,
        Map<ImportContractId, CompiledDataframeImportContract> contracts,
        ImportCatalogFingerprint fingerprint) {

    /** Snapshots maps and requires the catalog fingerprint. */
    public DataframeImportCatalog {
        sources = Map.copyOf(Objects.requireNonNull(sources, "sources"));
        authorityProfiles = Map.copyOf(Objects.requireNonNull(authorityProfiles, "authorityProfiles"));
        contracts = Map.copyOf(Objects.requireNonNull(contracts, "contracts"));
        Objects.requireNonNull(fingerprint, "fingerprint");
    }
}
