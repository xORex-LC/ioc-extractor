package com.iocextractor.platform.diagnostics;

/** Consumer-owned codes: the library tests have no dependency on the IOC catalog. */
public enum TestCodes implements DiagnosticCode {
    READ_FAILED(DiagnosticSeverity.FATAL, DiagnosticImpact.RUN),
    EMPTY_TEXT(DiagnosticSeverity.WARN, DiagnosticImpact.RUN),
    ITEM_SKIPPED(DiagnosticSeverity.WARN, DiagnosticImpact.ELEMENT),
    STAGE_FAILED(DiagnosticSeverity.ERROR, DiagnosticImpact.RUN),
    SOURCE_UNREADABLE(DiagnosticSeverity.ERROR, DiagnosticImpact.OPERATION),
    DIAGNOSTICS_SUPPRESSED(DiagnosticSeverity.WARN, DiagnosticImpact.RUN);

    private final DiagnosticSeverity severity;
    private final DiagnosticImpact impact;

    TestCodes(DiagnosticSeverity severity, DiagnosticImpact impact) {
        this.severity = severity;
        this.impact = impact;
    }

    @Override public String id() { return "TEST." + name(); }
    @Override public DiagnosticSeverity defaultSeverity() { return severity; }
    @Override public DiagnosticImpact impact() { return impact; }
}
