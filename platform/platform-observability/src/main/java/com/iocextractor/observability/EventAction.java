package com.iocextractor.observability;

/**
 * Stable machine-readable {@code event.action} values.
 *
 * <p>The generated logging catalog is derived from this enum. Values are a
 * public operational contract and must be added only with their first
 * production producer.
 */
public enum EventAction {
    APP_START("app_start", "app", "Application startup completed."),
    APP_STOP("app_stop", "app", "Application shutdown started."),
    COMMAND_START("command_start", "cli", "CLI command execution started."),
    COMMAND_COMPLETE("command_complete", "cli", "CLI command execution completed."),
    STAGE_START("stage_start", "pipeline", "Pipeline stage execution started."),
    STAGE_COMPLETE("stage_complete", "pipeline", "Pipeline stage execution completed or failed."),
    SOURCE_READ("source_read", "source", "Source document text was read."),
    ARTIFACT_PROJECT("artifact_project", "sink", "CSV projection was regenerated from canonical storage."),
    RETENTION_SWEEP("retention_sweep", "maintenance", "Retention sweep evaluated configured targets."),
    SCHEMA_MIGRATE("schema_migrate", "storage", "Database schema migration progressed or failed."),
    SCHEMA_VALIDATE("schema_validate", "storage", "Database schema reconciliation evaluated an artifact schema."),
    DB_OPEN("db_open", "storage", "Database connection was opened."),
    LEDGER_IMPORT("ledger_import", "storage", "Legacy ledger data was imported."),
    MAINTENANCE("maintenance", "maintenance", "Daemon maintenance cycle progressed or failed."),
    BACKFILL("backfill", "maintenance", "Backfill operation progressed or failed."),
    EXPORT_START("export_start", "export", "Export run acquired its durable work slot."),
    EXPORT_COMPLETE("export_complete", "export", "Export run reached a terminal checkpoint."),
    EXPORT_SLICE_WRITE("export_slice_write", "export", "Export slice files and manifest were written and verified."),
    EXPORT_RECOVER("export_recover", "export", "Incomplete durable export run recovery started."),
    SYNC_FETCH_START("sync_fetch_start", "sync", "Configured remote source fetch started."),
    SYNC_FETCH_COMPLETE("sync_fetch_complete", "sync", "Configured remote source fetch completed or was isolated as failed."),
    SYNC_PUBLISH_START("sync_publish_start", "sync", "Configured remote target publish started."),
    SYNC_PUBLISH_COMPLETE("sync_publish_complete", "sync", "Configured remote target publish completed or was isolated as failed."),
    SYNC_WORK_ADMISSION("sync_work_admission", "sync", "Keyed sync work was admitted or rejected."),
    SYNC_WORK_DISPATCH("sync_work_dispatch", "sync", "Keyed sync work was dispatched or completed."),
    EVENT_PUBLISH("event_publish", "events", "Control event publication was attempted."),
    EVENT_DISPATCH("event_dispatch", "events", "Control event dispatch was attempted."),
    DIAGNOSTIC_EMIT("diagnostic_emit", "diagnostics", "Processing diagnostic was emitted to the log stream.");

    private final String value;
    private final String area;
    private final String description;

    EventAction(String value, String area, String description) {
        this.value = value;
        this.area = area;
        this.description = description;
    }

    public String value() {
        return value;
    }

    /**
     * Returns the operational area that owns this action.
     *
     * @return action area
     */
    public String area() {
        return area;
    }

    /**
     * Returns the catalog description of this action.
     *
     * @return action description
     */
    public String description() {
        return description;
    }
}
