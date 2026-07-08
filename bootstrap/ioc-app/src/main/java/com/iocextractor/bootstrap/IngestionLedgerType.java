package com.iocextractor.bootstrap;

/** Ingestion ledger backend selector. */
public enum IngestionLedgerType implements ConfigSelector {
    FILE("file"),
    JDBC("jdbc");

    public static final String FILE_VALUE = "file";
    public static final String JDBC_VALUE = "jdbc";

    private final String token;

    IngestionLedgerType(String token) {
        this.token = token;
    }

    @Override
    public String token() {
        return token;
    }

    public static IngestionLedgerType parse(String value) {
        return ConfigSelectors.parse(IngestionLedgerType.class, value, "ioc.ingestion.ledger.type");
    }
}
