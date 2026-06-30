package com.iocextractor.observability;

/**
 * Stable machine-readable {@code event.action} values.
 */
public enum EventAction {
    APP_START("app_start"),
    APP_STOP("app_stop"),
    COMMAND_START("command_start"),
    COMMAND_COMPLETE("command_complete"),
    STAGE_START("stage_start"),
    STAGE_COMPLETE("stage_complete"),
    LOOKUP_LOAD("lookup_load"),
    SOURCE_READ("source_read"),
    ARTIFACT_WRITE("artifact_write"),
    AGGREGATION_START("aggregation_start"),
    AGGREGATION_COMPLETE("aggregation_complete"),
    RETENTION_SWEEP("retention_sweep"),
    SCHEMA_MIGRATE("schema_migrate"),
    SCHEMA_VALIDATE("schema_validate"),
    DB_OPEN("db_open"),
    LEDGER_IMPORT("ledger_import"),
    DB_HEALTH("db_health"),
    MAINTENANCE("maintenance"),
    BACKFILL("backfill"),
    EXPORT_START("export_start"),
    EXPORT_COMPLETE("export_complete"),
    EXPORT_SLICE_WRITE("export_slice_write"),
    EXPORT_RECOVER("export_recover"),
    SYNC_FETCH_START("sync_fetch_start"),
    SYNC_FETCH_COMPLETE("sync_fetch_complete"),
    SYNC_PUBLISH_START("sync_publish_start"),
    SYNC_PUBLISH_COMPLETE("sync_publish_complete"),
    REMOTE_FETCH("remote_fetch"),
    REMOTE_PUBLISH("remote_publish"),
    EVENT_PUBLISH("event_publish"),
    EVENT_DISPATCH("event_dispatch"),
    DIAGNOSTIC_EMIT("diagnostic_emit");

    private final String value;

    EventAction(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }
}
