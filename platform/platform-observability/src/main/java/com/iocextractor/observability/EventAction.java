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
    DIAGNOSTIC_EMIT("diagnostic_emit");

    private final String value;

    EventAction(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }
}
