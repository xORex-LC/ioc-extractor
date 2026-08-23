package com.iocextractor.application.dataframeimport.contract;

import com.iocextractor.application.dataframeimport.model.ImportContractFingerprint;
import com.iocextractor.application.dataframeimport.model.ImportContractId;

import java.util.Objects;

/**
 * Validated immutable contract and the fingerprint pinned by every delivery.
 *
 * @param id contract identity
 * @param version positive operator-managed version
 * @param definition validated normalized definition
 * @param fingerprint behavior fingerprint
 */
public record CompiledDataframeImportContract(
        ImportContractId id,
        int version,
        DataframeImportCatalogDraft.Contract definition,
        ImportContractFingerprint fingerprint) {

    /** Enforces the compiled-contract invariants. */
    public CompiledDataframeImportContract {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(fingerprint, "fingerprint");
        if (version < 1) {
            throw new IllegalArgumentException("Compiled import contract version must be positive");
        }
    }
}
