package com.iocextractor.adapter.out.store.jdbc;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Candidate-only drained conversion. One offline transaction replaces native tables and stamps format 13. */
final class JdbcStagedCanonicalMigration {
    static final int NATIVE_VERSION = 12;
    static final int STAGED_VERSION = 13;
    private JdbcStagedCanonicalMigration() { }

    static void requireVersion(Connection connection, int expected) throws SQLException {
        try (var statement = connection.createStatement(); var rows = statement.executeQuery("PRAGMA user_version")) {
            if (!rows.next() || rows.getInt(1) != expected) { throw new SQLException("Incompatible staged publication format"); }
        }
    }

    static JdbcTypedPublicationStore migrate(Connection connection, JdbcStagedCanonicalConfiguration configuration)
            throws SQLException {
        requireVersion(connection, NATIVE_VERSION);
        requireCatalog(connection, configuration);
        seedIds(connection, configuration);
        var store = JdbcTypedPublicationStore.migrateNative(connection, configuration.schemas(),
                configuration.clock().now(connection).value().toEpochMilli());
        JdbcPublicationTable.execute(connection, "CREATE TABLE publication_configuration("
                + "singleton_id INTEGER PRIMARY KEY CHECK(singleton_id=1),pins TEXT NOT NULL)");
        try (var statement = connection.prepareStatement("INSERT INTO publication_configuration VALUES(1,?)")) {
            statement.setString(1, store.pins(configuration.fingerprint())); statement.executeUpdate();
        }
        JdbcPublicationTable.execute(connection, "PRAGMA user_version=" + STAGED_VERSION);
        return store;
    }

    private static void requireCatalog(Connection connection, JdbcStagedCanonicalConfiguration configuration) throws SQLException {
        var identities = configuration.identities().stream().collect(java.util.stream.Collectors.toMap(
                com.iocextractor.application.artifact.ArtifactIdentityDefinition::artifactName, java.util.function.Function.identity()));
        var seen = new java.util.HashSet<String>();
        try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT artifact,identity_hash,epoch FROM artifact_identity")) {
            while (rows.next()) {
                var identity = identities.get(rows.getString(1));
                if (identity == null || !identity.identityHash().equals(rows.getString(2)) || identity.epoch() != rows.getLong(3)) {
                    throw new SQLException("Native identity catalog differs from staged migration pins");
                }
                seen.add(identity.artifactName());
            }
        }
        if (!seen.equals(identities.keySet())) { throw new SQLException("Incomplete native identity catalog"); }
        try (var statement = connection.prepareStatement("SELECT strategy,identity_epoch FROM artifact_id_allocator WHERE artifact=?")) {
            for (var definition : configuration.publicIds()) {
                statement.setString(1, definition.artifact());
                try (var rows = statement.executeQuery()) {
                    if (!rows.next() || !definition.strategy().name().equals(rows.getString(1)) || definition.identityEpoch() != rows.getLong(2)) {
                        throw new SQLException("Native public ID authority differs from staged migration pins");
                    }
                }
            }
        }
    }

    private static void seedIds(Connection connection, JdbcStagedCanonicalConfiguration configuration) throws SQLException {
        var ids = new JdbcPrivateIdReservations(configuration.dataSource(), configuration.allocatorClock(), configuration.admission());
        for (var schema : configuration.schemas()) {
            long epoch = configuration.identities().stream().filter(identity -> identity.artifactName().equals(schema.artifactName()))
                    .mapToLong(com.iocextractor.application.artifact.ArtifactIdentityDefinition::epoch).findFirst().orElseThrow();
            if (schema.columns().stream().noneMatch(column -> column.name().equals("id"))) {
                ids.initializeInMigration(connection, schema.artifactName(), epoch, false);
            }
            ids.initializeInMigration(connection, schema.artifactName(), epoch, true);
        }
    }

    static void copy(Connection connection, List<JdbcPublicationTable> tables, Set<String> artifacts,
                     long migrationMillis) throws SQLException {
        var protocol = new JdbcPublicationProtocol();
        var attempt = protocol.begin(connection, "migration:native-v12", "literal-native-facts-v1",
                new JdbcPublicationProtocol.Manifest(0, 0, Long.MAX_VALUE, Map.of()));
        for (var table : tables) { copyTable(connection, table, artifacts, attempt); }
        protocol.seal(connection, attempt);
        if (Thread.currentThread().isInterrupted()) { throw new SQLException("Staged migration interrupted"); }
        protocol.publish(connection, attempt, migrationMillis, "native-v12-adopted");
    }

    private static void copyTable(Connection connection, JdbcPublicationTable table, Set<String> artifacts,
                                  JdbcPublicationProtocol.Attempt attempt) throws SQLException {
        // SQL byte checks precede JDBC materialization; runtime transport supplies the same framing/NULL/address logic.
        String lengths = table.columns().stream().map(column -> "COALESCE(length(CAST(" + JdbcSql.quote(column) + " AS BLOB)),0)")
                .collect(java.util.stream.Collectors.joining("+"));
        try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT 1 FROM "
                + JdbcSql.quote(table.name()) + " WHERE (" + lengths + ")>1048576 LIMIT 1")) {
            if (rows.next()) { throw new SQLException("Native row exceeds staged migration byte limit"); }
        }
        var chunk = new JdbcTypedPublicationChunk(artifacts);
        try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT "
                + JdbcSql.joinedQuoted(table.columns()) + " FROM " + JdbcSql.quote(table.name()))) {
            while (rows.next()) {
                if (Thread.currentThread().isInterrupted()) { throw new SQLException("Staged migration interrupted"); }
                var values = new ArrayList<Object>(table.columns().size());
                for (int index = 1; index <= table.columns().size(); index++) { values.add(rows.getObject(index)); }
                var effect = new JdbcPrivateChanges.Effect(table, values, null, false, false);
                if (!chunk.add(effect, false)) {
                    chunk.copyMigrationRows(connection, attempt);
                    chunk = new JdbcTypedPublicationChunk(artifacts);
                    if (!chunk.add(effect, false)) { throw new SQLException("Native row exceeds migration chunk limit"); }
                }
            }
        }
        chunk.copyMigrationRows(connection, attempt);
    }
}
