package com.iocextractor.adapter.out.store.jdbc;

import com.iocextractor.application.artifact.lifecycle.LifecycleActivationState;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Set;

import static com.iocextractor.adapter.out.store.jdbc.JdbcSql.quote;
import static com.iocextractor.adapter.out.store.jdbc.JdbcPublicationTable.execute;

/** Candidate physical catalog. Initialization is explicit and refuses migration or mixed native writers. */
final class JdbcTypedPublicationStore {
    private final List<JdbcPublicationTable> tables;
    private final Set<String> artifacts;

    JdbcTypedPublicationStore(List<JdbcPublicationTable> tables, Set<String> artifacts) {
        this.tables = List.copyOf(tables);
        this.artifacts = Set.copyOf(artifacts);
    }

    static JdbcTypedPublicationStore initializeEmpty(Connection connection, List<DataframeArtifactSchema> schemas)
            throws SQLException {
        if (connection.getAutoCommit()) { throw new SQLException("Publication initialization requires an owned transaction"); }
        if (JdbcLifecycleTransactions.readActivationState(connection) != LifecycleActivationState.ACTIVE) {
            throw new SQLException("Staged publication requires active fixed lifecycle");
        }
        List<JdbcPublicationTable> tables = JdbcPublicationCatalog.inspectNative(connection, schemas);
        JdbcPublicationCatalog.requireEmpty(connection, tables);
        Set<String> artifacts = schemas.stream().map(DataframeArtifactSchema::artifactName)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        new JdbcPublicationProtocol().initialize(connection);
        new JdbcPublicationOwner().initialize(connection);
        JdbcPublicationCatalog.persist(connection, tables);
        for (var table : tables) { JdbcTypedVersionTable.create(connection, table, artifacts.contains(table.name())); }
        for (var table : tables.reversed()) { execute(connection, "DROP TABLE " + quote(table.name())); }
        for (var table : tables) { JdbcTypedVersionTable.createView(connection, table, artifacts.contains(table.name())); }
        return new JdbcTypedPublicationStore(tables, artifacts);
    }

    static JdbcTypedPublicationStore reopen(Connection connection, List<DataframeArtifactSchema> schemas) throws SQLException {
        List<JdbcPublicationTable> tables = JdbcPublicationCatalog.load(connection);
        Set<String> artifacts = schemas.stream().map(DataframeArtifactSchema::artifactName)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        for (String artifact : artifacts) {
            if (tables.stream().noneMatch(table -> table.name().equals(artifact))) {
                throw new SQLException("Publication artifact catalog changed; drained migration required");
            }
        }
        return new JdbcTypedPublicationStore(tables, artifacts);
    }

    List<JdbcPublicationTable> tables() { return tables; }
    Set<String> artifacts() { return artifacts; }
}
