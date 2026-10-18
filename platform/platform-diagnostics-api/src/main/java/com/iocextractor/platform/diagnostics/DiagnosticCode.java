package com.iocextractor.platform.diagnostics;

/**
 * Identifies a condition defined by the consuming service, usually as an enum.
 * Implementations must keep their ID, severity, impact, equality and hash code
 * stable. IDs need not use a particular naming scheme; the consumer owns their
 * uniqueness and compatibility. Collectors compare code objects, not ID strings.
 */
public interface DiagnosticCode {
    /** Identifies this condition across logs and reports.
     * @return a stable, nonblank identifier */
    String id();

    /** Supplies the severity when the producer does not override it.
     * @return the non-null severity used unless a producer overrides it */
    DiagnosticSeverity defaultSeverity();

    /** Declares which processing scope owns this condition.
     * @return the non-null scope affected by this condition */
    DiagnosticImpact impact();
}
