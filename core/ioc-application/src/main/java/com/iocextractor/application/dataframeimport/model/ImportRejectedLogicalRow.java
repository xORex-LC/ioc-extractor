package com.iocextractor.application.dataframeimport.model;

import java.util.List;
import java.util.Objects;

/** One rejected source-row outcome containing only safe diagnostic metadata. */
public record ImportRejectedLogicalRow(long sourceRowNumber, List<ImportRowIssue> issues) {

    /** Enforces one non-empty issue set belonging to the same physical row. */
    public ImportRejectedLogicalRow {
        if (sourceRowNumber < 1) {
            throw new IllegalArgumentException("Rejected import source row number must be positive");
        }
        issues = List.copyOf(Objects.requireNonNull(issues, "issues"));
        if (issues.isEmpty() || issues.stream().anyMatch(issue -> issue.sourceRowNumber() != sourceRowNumber)) {
            throw new IllegalArgumentException("Rejected import row requires same-row safe issues");
        }
    }
}
