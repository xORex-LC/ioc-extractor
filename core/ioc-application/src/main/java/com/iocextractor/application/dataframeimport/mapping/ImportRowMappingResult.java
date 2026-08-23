package com.iocextractor.application.dataframeimport.mapping;

import com.iocextractor.application.dataframeimport.model.ImportLogicalRow;
import com.iocextractor.application.dataframeimport.model.ImportRowIssue;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** All-or-nothing mapped logical row or its bounded safe issues. */
public record ImportRowMappingResult(Optional<ImportLogicalRow> row, List<ImportRowIssue> issues) {

    /** Enforces exactly one accepted/rejected representation. */
    public ImportRowMappingResult {
        row = Objects.requireNonNull(row, "row");
        issues = List.copyOf(Objects.requireNonNull(issues, "issues"));
        if (row.isPresent() == !issues.isEmpty()) {
            throw new IllegalArgumentException("Import row mapping must be accepted or rejected, exclusively");
        }
    }

    /** Creates an accepted mapping. */
    public static ImportRowMappingResult accepted(ImportLogicalRow row) {
        return new ImportRowMappingResult(Optional.of(Objects.requireNonNull(row, "row")), List.of());
    }

    /** Creates a rejected mapping. */
    public static ImportRowMappingResult rejected(List<ImportRowIssue> issues) {
        return new ImportRowMappingResult(Optional.empty(), issues);
    }
}
