package com.iocextractor.diagnostics.codes;

import com.iocextractor.diagnostics.DiagnosticCategory;
import com.iocextractor.diagnostics.DiagnosticCode;
import com.iocextractor.diagnostics.DiagnosticSeverity;

/**
 * Diagnostic codes for durable storage operations.
 */
public enum StorageDiagnosticCodes implements DiagnosticCode {
    MIGRATION_APPLIED(DiagnosticSeverity.INFO, "storage.migration-applied",
            "Storage {dbRole} applied schema migration {migrationVersion}; schema is now {schemaVersion}"),
    MIGRATION_ROLLBACK(DiagnosticSeverity.FATAL, "storage.migration-rollback",
            "Storage {dbRole} rolled back schema migration {migrationVersion}: {reason}"),
    MIGRATION_DOWNGRADE(DiagnosticSeverity.FATAL, "storage.migration-downgrade",
            "Storage {dbRole} schema version {fromVersion} is newer than supported version {toVersion}"),
    IMPORT_PARTIAL(DiagnosticSeverity.ERROR, "storage.import-partial",
            "Storage import {importName} from {sourcePath} stopped before completion: {reason}"),
    IMPORT_IDEMPOTENT_REPLAY(DiagnosticSeverity.INFO, "storage.import-idempotent-replay",
            "Storage import {importName} replay skipped already completed source {sourcePath}"),
    IDENTITY_DRIFT(DiagnosticSeverity.FATAL, "storage.identity-drift",
            "Artifact {artifact} identity drifted at epoch {identityEpoch}: {reason}"),
    IDENTITY_EPOCH_BUMP(DiagnosticSeverity.INFO, "storage.identity-epoch-bump",
            "Artifact {artifact} identity epoch bumped from {fromEpoch} to {toEpoch}");

    private final DiagnosticSeverity defaultSeverity;
    private final String messageKey;
    private final String defaultMessageTemplate;

    StorageDiagnosticCodes(DiagnosticSeverity defaultSeverity, String messageKey, String defaultMessageTemplate) {
        this.defaultSeverity = defaultSeverity;
        this.messageKey = messageKey;
        this.defaultMessageTemplate = defaultMessageTemplate;
    }

    @Override
    public String id() {
        return "STORAGE." + name();
    }

    @Override
    public DiagnosticCategory category() {
        return DiagnosticCategory.STORAGE;
    }

    @Override
    public DiagnosticSeverity defaultSeverity() {
        return defaultSeverity;
    }

    @Override
    public String messageKey() {
        return messageKey;
    }

    @Override
    public String defaultMessageTemplate() {
        return defaultMessageTemplate;
    }
}
