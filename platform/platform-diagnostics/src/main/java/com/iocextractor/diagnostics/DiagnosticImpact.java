package com.iocextractor.diagnostics;

/**
 * Processing unit affected by a diagnostic occurrence.
 *
 * <p>Impact is independent of severity: a run-scoped diagnostic may be an
 * advisory warning, while an element-scoped diagnostic may be an error that a
 * collect-and-continue policy permits.
 */
public enum DiagnosticImpact {
    /** One data element can be omitted without invalidating the remaining run. */
    ELEMENT,
    /** The diagnostic describes the current source or pipeline run as a whole. */
    RUN,
    /** The diagnostic belongs to an operation governed by its own state machine. */
    OPERATION
}
