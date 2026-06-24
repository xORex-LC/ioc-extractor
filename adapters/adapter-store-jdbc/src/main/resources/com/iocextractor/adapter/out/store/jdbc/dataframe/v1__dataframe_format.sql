CREATE TABLE dataframe_schema_format (
    name TEXT PRIMARY KEY,
    value TEXT NOT NULL
);

INSERT INTO dataframe_schema_format(name, value)
VALUES ('format', 'dataframe-v1');
