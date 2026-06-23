package com.iocextractor.observability;

/**
 * Stable ECS and project-specific log field names.
 */
public enum LogField {
    EVENT_ACTION("event.action"),
    EVENT_TYPE("event.type"),
    EVENT_OUTCOME("event.outcome"),
    EVENT_DURATION("event.duration"),
    FILE_PATH("file.path"),
    IOC_RUN_ID("ioc.run.id"),
    IOC_SOURCE_ID("ioc.source.id"),
    IOC_MODE("ioc.mode"),
    IOC_STAGE("ioc.stage"),
    IOC_SOURCE_PATH("ioc.source.path"),
    IOC_SOURCE_CONTENT_HASH("ioc.source.content_hash"),
    IOC_ARTIFACT_NAME("ioc.artifact.name"),
    IOC_ROWS("ioc.rows"),
    IOC_DB_ROLE("ioc.db.role"),
    IOC_SCHEMA_VERSION("ioc.schema.version"),
    IOC_MIGRATION_VERSION("ioc.migration.version"),
    IOC_IDENTITY_EPOCH("ioc.identity.epoch"),
    IOC_AFFECTED_ROWS("ioc.affected_rows"),
    IOC_DIAGNOSTIC_CODE("ioc.diagnostic.code"),
    IOC_DIAGNOSTIC_CATEGORY("ioc.diagnostic.category"),
    IOC_DIAGNOSTIC_SEVERITY("ioc.diagnostic.severity");

    private final String key;

    LogField(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }
}
