package com.iocextractor.application.dataframeimport.model;

import com.iocextractor.diagnostics.codes.ImportDiagnosticCodes;

import java.util.Objects;

/** Value-free result of one positive source capability probe. */
public record ImportSourceReadiness(
        ImportSourceId sourceId,
        ImportSourceReadinessPhase phase,
        ImportSourceReadinessStatus status,
        String diagnosticCode,
        boolean retryEligible) {

    /** Validates stable readiness evidence without endpoint or path data. */
    public ImportSourceReadiness {
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(phase, "phase");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(diagnosticCode, "diagnosticCode");
        if (diagnosticCode.isBlank()) {
            throw new IllegalArgumentException("Import source readiness code must not be blank");
        }
        if (status == ImportSourceReadinessStatus.READY && retryEligible) {
            throw new IllegalArgumentException("Ready import source cannot require retry");
        }
    }

    /** Creates the common successful capability result. */
    public static ImportSourceReadiness ready(ImportSourceId sourceId) {
        return new ImportSourceReadiness(sourceId, ImportSourceReadinessPhase.COMPLETE,
                ImportSourceReadinessStatus.READY, "IMPORT.SOURCE_READY", false);
    }

    /** Creates value-free evidence for an unavailable or incompatible capability. */
    public static ImportSourceReadiness capabilityFailed(
            ImportSourceId sourceId,
            ImportSourceReadinessPhase phase,
            boolean retryEligible) {
        return new ImportSourceReadiness(sourceId, phase,
                retryEligible ? ImportSourceReadinessStatus.TRANSIENTLY_UNAVAILABLE
                        : ImportSourceReadinessStatus.INCOMPATIBLE,
                ImportDiagnosticCodes.SOURCE_CAPABILITY_FAILED.id(), retryEligible);
    }

    /** Creates value-free evidence for a missing or incompatible private namespace. */
    public static ImportSourceReadiness namespaceIncompatible(ImportSourceId sourceId) {
        return new ImportSourceReadiness(sourceId, ImportSourceReadinessPhase.NAMESPACE,
                ImportSourceReadinessStatus.INCOMPATIBLE,
                ImportDiagnosticCodes.SOURCE_NAMESPACE_INCOMPATIBLE.id(), false);
    }
}
