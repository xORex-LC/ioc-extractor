CREATE TABLE import_row_warning (
    delivery_id TEXT NOT NULL,
    warning_ordinal INTEGER NOT NULL CHECK (warning_ordinal > 0),
    source_row_number INTEGER NOT NULL CHECK (source_row_number > 0),
    artifact TEXT,
    diagnostic_code TEXT NOT NULL,
    PRIMARY KEY (delivery_id, warning_ordinal),
    FOREIGN KEY (delivery_id) REFERENCES import_commit(delivery_id) ON DELETE CASCADE
);

CREATE INDEX ix_import_warning_delivery
ON import_row_warning(delivery_id, source_row_number);
