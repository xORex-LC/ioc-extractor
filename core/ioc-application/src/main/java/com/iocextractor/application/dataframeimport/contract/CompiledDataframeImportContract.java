package com.iocextractor.application.dataframeimport.contract;

import com.iocextractor.application.dataframeimport.model.ImportContractFingerprint;
import com.iocextractor.application.dataframeimport.model.ImportContractId;
import com.iocextractor.application.dataframeimport.model.DelimitedDialect;

import java.util.Objects;

/**
 * Validated immutable contract and the fingerprint pinned by every delivery.
 *
 * @param id contract identity
 * @param version positive operator-managed version
 * @param definition validated normalized definition
 * @param dialect executable parser-independent delimiter grammar
 * @param fingerprint behavior fingerprint
 */
public record CompiledDataframeImportContract(
        ImportContractId id,
        int version,
        DataframeImportCatalogDraft.Contract definition,
        DelimitedDialect dialect,
        ImportContractFingerprint fingerprint) {

    /** Enforces the compiled-contract invariants. */
    public CompiledDataframeImportContract {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(dialect, "dialect");
        Objects.requireNonNull(fingerprint, "fingerprint");
        if (version < 1) {
            throw new IllegalArgumentException("Compiled import contract version must be positive");
        }
    }
}
