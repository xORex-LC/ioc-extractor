package com.iocextractor.observability;

/**
 * Stable ECS and project-specific log field names.
 *
 * <p>The generated logging catalog is derived from this enum. Project-specific
 * fields belong under the {@code ioc.*} namespace.
 */
public enum LogField {
    EVENT_ACTION("event.action", LogValueType.STRING, "Stable machine-readable action of the event."),
    EVENT_TYPE("event.type", LogValueType.STRING, "ECS event type classification."),
    EVENT_OUTCOME("event.outcome", LogValueType.STRING, "Stable success, failure, or unknown outcome."),
    EVENT_DURATION("event.duration", LogValueType.LONG, "Event duration in nanoseconds."),
    ERROR_TYPE("error.type", LogValueType.STRING, "Exception class associated with a failed operation."),
    FILE_PATH("file.path", LogValueType.STRING, "Input or output file path."),
    IOC_RUN_ID("ioc.run.id", LogValueType.STRING, "Unique pipeline run identifier."),
    IOC_SOURCE_ID("ioc.source.id", LogValueType.STRING, "Logical source identifier."),
    IOC_MODE("ioc.mode", LogValueType.STRING, "Runtime mode: oneshot or daemon."),
    IOC_STAGE("ioc.stage", LogValueType.STRING, "Current pipeline stage identifier."),
    IOC_DECISION_KIND("ioc.decision.kind", LogValueType.STRING,
            "Pipeline processing step that made a per-item decision."),
    IOC_DECISION_OUTCOME("ioc.decision.outcome", LogValueType.STRING,
            "Stable outcome of a per-item pipeline decision."),
    IOC_DECISION_RULE("ioc.decision.rule", LogValueType.STRING,
            "Rule or source marker selected by a pipeline decision."),
    IOC_DECISION_PATTERN("ioc.decision.pattern", LogValueType.STRING,
            "Pattern or predicate set evaluated by a pipeline decision."),
    IOC_DECISION_RESULT("ioc.decision.result", LogValueType.STRING,
            "Compact materialized result of a pipeline decision."),
    IOC_ITEM_IDENTITY("ioc.item.identity", LogValueType.STRING,
            "Safe short identity of the item involved in a pipeline decision."),
    IOC_ITEM_VALUE("ioc.item.value", LogValueType.STRING,
            "TRACE-only item value with query-like data redacted."),
    IOC_INDICATOR_TYPE("ioc.indicator.type", LogValueType.STRING,
            "IOC type involved in a pipeline decision."),
    IOC_SPAN_START("ioc.span.start", LogValueType.LONG,
            "Inclusive source-text offset of a matched item."),
    IOC_SPAN_END("ioc.span.end", LogValueType.LONG,
            "Exclusive source-text offset of a matched item."),
    IOC_SOURCE_PATH("ioc.source.path", LogValueType.STRING, "Normalized source document path."),
    IOC_SOURCE_CONTENT_HASH("ioc.source.content_hash", LogValueType.STRING,
            "Content hash of the source document."),
    IOC_INGEST_DISPOSITION("ioc.ingest.disposition", LogValueType.STRING,
            "Stable terminal disposition of a source ingestion attempt."),
    IOC_INGEST_RECOVERED_RUNS("ioc.ingest.recovered_runs", LogValueType.LONG,
            "Number of incomplete ingest runs examined during startup recovery."),
    IOC_INGEST_RECOVERED_SOURCES("ioc.ingest.recovered_sources", LogValueType.LONG,
            "Number of incomplete source records handled during startup recovery."),
    IOC_ARTIFACT_NAME("ioc.artifact.name", LogValueType.STRING, "Configured artifact name."),
    IOC_ROWS("ioc.rows", LogValueType.LONG, "Number of rows in the operation."),
    IOC_DB_ROLE("ioc.db.role", LogValueType.STRING, "Logical database role."),
    IOC_SCHEMA_VERSION("ioc.schema.version", LogValueType.LONG, "Observed database schema version."),
    IOC_MIGRATION_VERSION("ioc.migration.version", LogValueType.LONG, "Schema migration version."),
    IOC_IDENTITY_EPOCH("ioc.identity.epoch", LogValueType.LONG, "Artifact identity epoch."),
    IOC_LEGACY_IMPORT_NAME("ioc.legacy_import.name", LogValueType.STRING, "Legacy import source name."),
    IOC_LEGACY_IMPORT_SCANNED("ioc.legacy_import.scanned", LogValueType.LONG,
            "Legacy records scanned during import."),
    IOC_LEGACY_IMPORT_IMPORTED("ioc.legacy_import.imported", LogValueType.LONG,
            "Legacy records imported."),
    IOC_LEGACY_IMPORT_SKIPPED("ioc.legacy_import.skipped", LogValueType.LONG,
            "Legacy records skipped during import."),
    IOC_LEGACY_IMPORT_FAILED("ioc.legacy_import.failed", LogValueType.LONG,
            "Legacy records that failed import."),
    IOC_STORAGE_SQLITE_TUNING("ioc.storage.sqlite.tuning", LogValueType.STRING,
            "Selected SQLite tuning profile."),
    IOC_STORAGE_SQLITE_MAX_POOL_SIZE("ioc.storage.sqlite.max_pool_size", LogValueType.LONG,
            "Configured maximum SQLite connection pool size."),
    IOC_STORAGE_SQLITE_WRITE_MAX("ioc.storage.sqlite.write_max", LogValueType.LONG,
            "Configured maximum concurrent SQLite writers."),
    IOC_STORAGE_SQLITE_READ_MAX("ioc.storage.sqlite.read_max", LogValueType.LONG,
            "Configured maximum concurrent SQLite readers."),
    IOC_STORAGE_SQLITE_BUSY_TIMEOUT_MS("ioc.storage.sqlite.busy_timeout_ms", LogValueType.LONG,
            "Effective SQLite busy timeout in milliseconds."),
    IOC_EXPORT_PROFILE("ioc.export.profile", LogValueType.STRING, "Configured immutable export profile."),
    IOC_EXPORT_SLICE_ID("ioc.export.slice.id", LogValueType.STRING,
            "Immutable export slice identifier."),
    IOC_EXPORT_REVISION("ioc.export.revision", LogValueType.LONG,
            "Maximum canonical revision covered by an export slice."),
    IOC_EVENT_ID("ioc.event.id", LogValueType.STRING, "Stable control-event identifier."),
    IOC_EVENT_TYPE("ioc.event.type", LogValueType.STRING, "Stable project control-event type."),
    IOC_EVENT_VERSION("ioc.event.version", LogValueType.LONG, "Control-event payload contract version."),
    IOC_EVENT_CORRELATION_ID("ioc.event.correlation_id", LogValueType.STRING,
            "Control-event correlation identifier."),
    IOC_EVENT_CAUSATION_ID("ioc.event.causation_id", LogValueType.STRING,
            "Event or command identifier that caused this event."),
    IOC_EVENT_HANDLER("ioc.event.handler", LogValueType.STRING, "Local control-event handler name."),
    IOC_SYNC_ENDPOINT("ioc.sync.endpoint", LogValueType.STRING,
            "Logical sync endpoint name without transport secrets."),
    IOC_SYNC_FILES("ioc.sync.files", LogValueType.LONG, "Number of files in the sync operation."),
    IOC_SYNC_TARGET("ioc.sync.target", LogValueType.STRING, "Logical publish target name."),
    IOC_SYNC_KEY("ioc.sync.key", LogValueType.STRING, "Key used to serialize sync work."),
    IOC_SYNC_QUEUE_DEPTH("ioc.sync.queue_depth", LogValueType.LONG,
            "Number of queued sync work items."),
    IOC_SYNC_SHED_TO_RECONCILE("ioc.sync.shed_to_reconcile", LogValueType.BOOLEAN,
            "Whether work was shed to periodic reconciliation."),
    IOC_SYNC_ABANDONED_WORK("ioc.sync.abandoned_work", LogValueType.LONG,
            "Number of abandoned sync work items."),
    IOC_COMPLETION_STATUS("ioc.completion.status", LogValueType.STRING,
            "Typed completion status of a structurally finished processing run."),
    IOC_DIAGNOSTIC_TOTAL("ioc.diagnostic.total", LogValueType.LONG,
            "Total diagnostics observed during the run, including suppressed occurrences."),
    IOC_DIAGNOSTIC_SUPPRESSED("ioc.diagnostic.suppressed", LogValueType.LONG,
            "Diagnostics suppressed by the per-run retention budget."),
    IOC_DIAGNOSTIC_FATAL_COUNT("ioc.diagnostic.count.fatal", LogValueType.LONG,
            "FATAL diagnostics observed during the run."),
    IOC_DIAGNOSTIC_ERROR_COUNT("ioc.diagnostic.count.error", LogValueType.LONG,
            "ERROR diagnostics observed during the run."),
    IOC_DIAGNOSTIC_WARN_COUNT("ioc.diagnostic.count.warn", LogValueType.LONG,
            "WARN diagnostics observed during the run."),
    IOC_DIAGNOSTIC_INFO_COUNT("ioc.diagnostic.count.info", LogValueType.LONG,
            "INFO diagnostics observed during the run."),
    IOC_DIAGNOSTIC_DEBUG_COUNT("ioc.diagnostic.count.debug", LogValueType.LONG,
            "DEBUG diagnostics observed during the run."),
    IOC_DIAGNOSTIC_TRACE_COUNT("ioc.diagnostic.count.trace", LogValueType.LONG,
            "TRACE diagnostics observed during the run."),
    IOC_DIAGNOSTIC_CODE("ioc.diagnostic.code", LogValueType.STRING, "Stable diagnostic code."),
    IOC_DIAGNOSTIC_CATEGORY("ioc.diagnostic.category", LogValueType.STRING,
            "Diagnostic processing category."),
    IOC_DIAGNOSTIC_SEVERITY("ioc.diagnostic.severity", LogValueType.STRING,
            "Diagnostic severity independent of log level.");

    private final String key;
    private final LogValueType valueType;
    private final String description;

    LogField(String key, LogValueType valueType, String description) {
        this.key = key;
        this.valueType = valueType;
        this.description = description;
    }

    public String key() {
        return key;
    }

    /**
     * Returns the stable JSON scalar type of this field.
     *
     * @return JSON scalar type
     */
    public LogValueType valueType() {
        return valueType;
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
