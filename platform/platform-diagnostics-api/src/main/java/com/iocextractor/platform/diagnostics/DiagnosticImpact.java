package com.iocextractor.platform.diagnostics;

/**
 * Processing unit affected by a diagnostic occurrence.
 *
 * <p>Impact is independent of severity: a run-scoped diagnostic may be an
 * advisory warning, while an element-scoped diagnostic may be an error that a
 * collect-and-continue policy permits. Scope alone never authorizes skipping data,
 * committing a write or retrying an operation; the caller owns those decisions.
 */
public enum DiagnosticImpact {
    /** The condition concerns one data element; bounded collectors charge it to the budget. */
    ELEMENT,
    /** The condition concerns the current run; bounded collectors charge it to the budget. */
    RUN,
    /** An operation owns recovery; occurrences bypass the detail budget and may grow memory. */
    OPERATION
}
