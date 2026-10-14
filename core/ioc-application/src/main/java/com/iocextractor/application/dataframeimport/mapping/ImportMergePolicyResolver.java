package com.iocextractor.application.dataframeimport.mapping;

import com.iocextractor.application.dataframeimport.contract.CompiledDataframeImportContract;
import com.iocextractor.application.dataframeimport.contract.DataframeImportCatalogDraft;
import com.iocextractor.application.dataframeimport.model.ImportMergePolicy;

/** Resolves the same column, artifact and contract merge precedence for both import preparers. */
public final class ImportMergePolicyResolver {
    private ImportMergePolicyResolver() { }

    /** Returns the effective policy, including for an artifact-schema field absent from CSV inputs. */
    public static ImportMergePolicy resolve(CompiledDataframeImportContract contract,
                                            DataframeImportCatalogDraft.Artifact artifact,
                                            String target) {
        return artifact.columns().stream()
                .filter(column -> column.target().equals(target))
                .map(column -> resolve(contract, artifact, column))
                .findFirst()
                .orElseGet(() -> defaultPolicy(contract, artifact));
    }

    /** Resolves a declared column directly, avoiding repeated catalog scans while admitting rows. */
    public static ImportMergePolicy resolve(CompiledDataframeImportContract contract,
                                            DataframeImportCatalogDraft.Artifact artifact,
                                            DataframeImportCatalogDraft.Column column) {
        return column.mergePolicy() == null ? defaultPolicy(contract, artifact) : column.mergePolicy();
    }

    private static ImportMergePolicy defaultPolicy(CompiledDataframeImportContract contract,
                                                    DataframeImportCatalogDraft.Artifact artifact) {
        return artifact.mergeDefault() == null
                ? contract.definition().mergeDefault() : artifact.mergeDefault();
    }
}
