package com.iocextractor.application.dataframeimport.contract;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Collect-all catalog compilation outcome.
 *
 * @param catalog compiled catalog when no violations exist
 * @param violations ordered safe semantic violations
 */
public record DataframeImportCatalogCompilation(
        Optional<DataframeImportCatalog> catalog,
        List<ImportContractViolation> violations) {

    /** Snapshots the outcome containers and enforces all-or-none validity. */
    public DataframeImportCatalogCompilation {
        catalog = Objects.requireNonNull(catalog, "catalog");
        violations = List.copyOf(Objects.requireNonNull(violations, "violations"));
        boolean inconsistentSuccess = catalog.isPresent() && !violations.isEmpty();
        boolean inconsistentFailure = catalog.isEmpty() && violations.isEmpty();
        if (inconsistentSuccess || inconsistentFailure) {
            throw new IllegalArgumentException("Catalog is present exactly when compilation has no violations");
        }
    }

    /** @return whether compilation produced an executable catalog */
    public boolean valid() {
        return catalog.isPresent();
    }

    /**
     * Returns the valid catalog or fails with a safe aggregate message.
     *
     * @return compiled catalog
     * @throws IllegalStateException when violations exist
     */
    public DataframeImportCatalog catalogOrThrow() {
        if (catalog.isPresent()) {
            return catalog.orElseThrow();
        }
        throw new IllegalStateException("Invalid dataframe import catalog:\n- "
                + violations.stream()
                .map(violation -> violation.path() + ": " + violation.message())
                .reduce((left, right) -> left + "\n- " + right)
                .orElse("unknown violation"));
    }
}
