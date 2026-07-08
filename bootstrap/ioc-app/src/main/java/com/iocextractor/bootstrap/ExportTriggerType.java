package com.iocextractor.bootstrap;

/** Daemon export cadence trigger selector. */
public enum ExportTriggerType implements ConfigSelector {
    INTERVAL("interval"),
    QUIET_PERIOD("quiet-period");

    public static final String INTERVAL_VALUE = "interval";
    public static final String QUIET_PERIOD_VALUE = "quiet-period";

    private final String token;

    ExportTriggerType(String token) {
        this.token = token;
    }

    @Override
    public String token() {
        return token;
    }

    public boolean isQuietPeriod() {
        return this == QUIET_PERIOD;
    }

    public static ExportTriggerType parse(String value) {
        return ConfigSelectors.parse(ExportTriggerType.class, value, "ioc.export.trigger.type");
    }
}
