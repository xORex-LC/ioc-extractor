package com.iocextractor.application.dataframeimport.mapping;

import com.iocextractor.application.dataframeimport.model.ImportLogicalRow;
import com.iocextractor.application.dataframeimport.model.ImportRowIssue;
import com.iocextractor.application.dataframeimport.model.ImportRowWarning;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** All-or-nothing mapped logical row or its bounded safe issues. */
public record ImportRowMappingResult(Optional<ImportLogicalRow> row, List<ImportRowIssue> issues,
                                     List<ImportRowWarning> warnings) {

    /** Enforces exactly one accepted/rejected representation. */
    public ImportRowMappingResult {
        row = Objects.requireNonNull(row, "row");
        issues = List.copyOf(Objects.requireNonNull(issues, "issues"));
        warnings = List.copyOf(Objects.requireNonNull(warnings, "warnings"));
        if (row.isPresent() == !issues.isEmpty()) {
            throw new IllegalArgumentException("Import row mapping must be accepted or rejected, exclusively");
        }
        if (row.isEmpty() && !warnings.isEmpty()) {
            throw new IllegalArgumentException("Rejected import row cannot carry accepted warnings");
        }
    }

    /** Creates an accepted mapping. */
    public static ImportRowMappingResult accepted(ImportLogicalRow row) {
        return accepted(row, List.of());
    }

    /** Creates an accepted mapping with value-free warnings. */
    public static ImportRowMappingResult accepted(ImportLogicalRow row, List<ImportRowWarning> warnings) {
        return new ImportRowMappingResult(Optional.of(Objects.requireNonNull(row, "row")),
                List.of(), warnings);
    }

    /** Creates a rejected mapping. */
    public static ImportRowMappingResult rejected(List<ImportRowIssue> issues) {
        return new ImportRowMappingResult(Optional.empty(), issues, List.of());
    }
}
