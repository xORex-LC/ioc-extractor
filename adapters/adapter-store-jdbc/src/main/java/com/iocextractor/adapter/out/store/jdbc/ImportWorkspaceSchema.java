package com.iocextractor.adapter.out.store.jdbc;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/** Private SQLite staging schema; versioned independently from service/dataframe stores. */
final class ImportWorkspaceSchema {

    static final int VERSION = 1;

    private static final String CREATE_META = """
            CREATE TABLE stage_meta (
                delivery_id TEXT PRIMARY KEY,
                schema_version INTEGER NOT NULL,
                snapshot_sha256 TEXT NOT NULL,
                snapshot_size INTEGER NOT NULL,
                contract_id TEXT NOT NULL,
                contract_version INTEGER NOT NULL,
                contract_fingerprint TEXT NOT NULL,
                duplicate_policy TEXT NOT NULL,
                source_row_count INTEGER NOT NULL DEFAULT 0,
                logical_row_count INTEGER NOT NULL DEFAULT 0,
                accepted_count INTEGER NOT NULL DEFAULT 0,
                rejected_count INTEGER NOT NULL DEFAULT 0,
                plan_hash TEXT NOT NULL,
                sealed_at_ms INTEGER
            )
            """;
    private static final String CREATE_INPUT_ROW = """
            CREATE TABLE stage_input_row (
                source_row_number INTEGER PRIMARY KEY,
                group_key_hash TEXT,
                group_key_canonical TEXT,
                status TEXT NOT NULL,
                error_count INTEGER NOT NULL DEFAULT 0
            )
            """;
    private static final String CREATE_BRANCH = """
            CREATE TABLE stage_branch (
                branch_id INTEGER PRIMARY KEY AUTOINCREMENT,
                source_row_number INTEGER NOT NULL,
                branch_ordinal INTEGER NOT NULL,
                artifact TEXT NOT NULL,
                primary_flag INTEGER NOT NULL,
                requested_slot INTEGER,
                record_definition_id TEXT NOT NULL,
                record_key_hash TEXT NOT NULL,
                record_key_canonical TEXT NOT NULL,
                status TEXT NOT NULL,
                UNIQUE(source_row_number, branch_ordinal),
                FOREIGN KEY(source_row_number) REFERENCES stage_input_row(source_row_number) ON DELETE CASCADE
            )
            """;
    private static final String CREATE_CELL = """
            CREATE TABLE stage_cell (
                branch_id INTEGER NOT NULL,
                target_column TEXT NOT NULL,
                presence INTEGER NOT NULL CHECK (presence BETWEEN 0 AND 2),
                value TEXT,
                PRIMARY KEY(branch_id, target_column),
                FOREIGN KEY(branch_id) REFERENCES stage_branch(branch_id) ON DELETE CASCADE,
                CHECK ((presence = 2) = (value IS NOT NULL))
            )
            """;
    private static final String CREATE_MATCH_KEY = """
            CREATE TABLE stage_match_key (
                branch_id INTEGER NOT NULL,
                definition_id TEXT NOT NULL,
                key_hash TEXT NOT NULL,
                key_canonical TEXT NOT NULL,
                PRIMARY KEY(branch_id, definition_id, key_hash, key_canonical),
                FOREIGN KEY(branch_id) REFERENCES stage_branch(branch_id) ON DELETE CASCADE
            )
            """;
    private static final String CREATE_ROW_ERROR = """
            CREATE TABLE stage_row_error (
                error_id INTEGER PRIMARY KEY AUTOINCREMENT,
                logical_group_id INTEGER NOT NULL,
                source_row_number INTEGER NOT NULL,
                artifact TEXT,
                diagnostic_code TEXT NOT NULL,
                FOREIGN KEY(source_row_number) REFERENCES stage_input_row(source_row_number) ON DELETE CASCADE
            )
            """;

    private static final String CREATE_INPUT_GROUP_INDEX = """
            CREATE INDEX ix_stage_input_group
            ON stage_input_row(group_key_hash, group_key_canonical, source_row_number)
            """;
    private static final String CREATE_BRANCH_GROUP_INDEX = """
            CREATE INDEX ix_stage_branch_group
            ON stage_branch(source_row_number, branch_ordinal, artifact)
            """;
    private static final String CREATE_MATCH_LOOKUP_INDEX = """
            CREATE INDEX ix_stage_match_lookup
            ON stage_match_key(definition_id, key_hash, key_canonical, branch_id)
            """;
    private static final String CREATE_ERROR_ROW_INDEX = """
            CREATE INDEX ix_stage_error_row
            ON stage_row_error(source_row_number, error_id)
            """;

    private ImportWorkspaceSchema() {
    }

    static void create(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys=ON");
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA synchronous=FULL");
            statement.execute("PRAGMA temp_store=FILE");
            statement.execute(CREATE_META);
            statement.execute(CREATE_INPUT_ROW);
            statement.execute(CREATE_BRANCH);
            statement.execute(CREATE_CELL);
            statement.execute(CREATE_MATCH_KEY);
            statement.execute(CREATE_ROW_ERROR);
            statement.execute("PRAGMA user_version=" + VERSION);
        }
    }

    static void createSealIndexes(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(CREATE_INPUT_GROUP_INDEX);
            statement.execute(CREATE_BRANCH_GROUP_INDEX);
            statement.execute(CREATE_MATCH_LOOKUP_INDEX);
            statement.execute(CREATE_ERROR_ROW_INDEX);
        }
    }
}
