package com.iocextractor.observability;

/**
 * Stable ECS and project-specific log field names.
 *
 * <p>The generated logging catalog is derived from this enum. Project-specific
 * fields belong under the {@code ioc.*} namespace.
 */
public enum LogField {
    EVENT_ACTION("event.action", "Stable machine-readable action of the event."),
    EVENT_TYPE("event.type", "ECS event type classification."),
    EVENT_OUTCOME("event.outcome", "Stable success, failure, or unknown outcome."),
    EVENT_DURATION("event.duration", "Event duration in nanoseconds."),
    FILE_PATH("file.path", "Input or output file path."),
    IOC_RUN_ID("ioc.run.id", "Unique pipeline run identifier."),
    IOC_SOURCE_ID("ioc.source.id", "Logical source identifier."),
    IOC_MODE("ioc.mode", "Runtime mode: oneshot or daemon."),
    IOC_STAGE("ioc.stage", "Current pipeline stage identifier."),
    IOC_SOURCE_PATH("ioc.source.path", "Normalized source document path."),
    IOC_SOURCE_CONTENT_HASH("ioc.source.content_hash", "Content hash of the source document."),
    IOC_ARTIFACT_NAME("ioc.artifact.name", "Configured artifact name."),
    IOC_ROWS("ioc.rows", "Number of rows in the operation."),
    IOC_DB_ROLE("ioc.db.role", "Logical database role."),
    IOC_SCHEMA_VERSION("ioc.schema.version", "Observed database schema version."),
    IOC_MIGRATION_VERSION("ioc.migration.version", "Schema migration version."),
    IOC_IDENTITY_EPOCH("ioc.identity.epoch", "Artifact identity epoch."),
    IOC_AFFECTED_ROWS("ioc.affected_rows", "Number of rows affected by an operation."),
    IOC_EXPORT_PROFILE("ioc.export.profile", "Configured immutable export profile."),
    IOC_EXPORT_SLICE_ID("ioc.export.slice.id", "Immutable export slice identifier."),
    IOC_EXPORT_REVISION("ioc.export.revision", "Maximum canonical revision covered by an export slice."),
    IOC_EVENT_ID("ioc.event.id", "Stable control-event identifier."),
    IOC_EVENT_TYPE("ioc.event.type", "Stable project control-event type."),
    IOC_EVENT_VERSION("ioc.event.version", "Control-event payload contract version."),
    IOC_EVENT_CORRELATION_ID("ioc.event.correlation_id", "Control-event correlation identifier."),
    IOC_EVENT_CAUSATION_ID("ioc.event.causation_id", "Event or command identifier that caused this event."),
    IOC_EVENT_HANDLER("ioc.event.handler", "Local control-event handler name."),
    IOC_SYNC_ENDPOINT("ioc.sync.endpoint", "Logical sync endpoint name without transport secrets."),
    IOC_SYNC_FILES("ioc.sync.files", "Number of files in the sync operation."),
    IOC_SYNC_TARGET("ioc.sync.target", "Logical publish target name."),
    IOC_SYNC_KEY("ioc.sync.key", "Key used to serialize sync work."),
    IOC_SYNC_QUEUE_DEPTH("ioc.sync.queue_depth", "Number of queued sync work items."),
    IOC_SYNC_RUNNING("ioc.sync.running", "Number of active sync work items."),
    IOC_SYNC_OLDEST_AGE("ioc.sync.oldest_age", "Age of the oldest pending sync work item."),
    IOC_SYNC_SHED_TO_RECONCILE("ioc.sync.shed_to_reconcile", "Whether work was shed to periodic reconciliation."),
    IOC_SYNC_ABANDONED_WORK("ioc.sync.abandoned_work", "Number of abandoned sync work items."),
    IOC_DIAGNOSTIC_CODE("ioc.diagnostic.code", "Stable diagnostic code."),
    IOC_DIAGNOSTIC_CATEGORY("ioc.diagnostic.category", "Diagnostic processing category."),
    IOC_DIAGNOSTIC_SEVERITY("ioc.diagnostic.severity", "Diagnostic severity independent of log level.");

    private final String key;
    private final String description;

    LogField(String key, String description) {
        this.key = key;
        this.description = description;
    }

    public String key() {
        return key;
    }

    /**
     * Returns the catalog description of this field.
     *
     * @return field description
     */
    public String description() {
        return description;
    }
}
