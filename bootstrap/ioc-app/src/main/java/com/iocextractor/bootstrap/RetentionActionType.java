package com.iocextractor.bootstrap;

/** Maintenance retention action selector. */
public enum RetentionActionType implements ConfigSelector {
    DELETE("delete"),
    ARCHIVE("archive");

    public static final String DELETE_VALUE = "delete";
    public static final String ARCHIVE_VALUE = "archive";

    private final String token;

    RetentionActionType(String token) {
        this.token = token;
    }

    @Override
    public String token() {
        return token;
    }

    public static RetentionActionType parse(String value) {
        return ConfigSelectors.parse(RetentionActionType.class, value, "ioc.maintenance.retention.targets[].action");
    }
}
