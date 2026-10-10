package com.iocextractor.adapter.out.store.jdbc;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

/** Native hydration catalog: affected row graphs, receipt graphs and explicitly selected control scopes. */
final class JdbcSelectedCanonicalBase {
    private final JdbcPublicationHydrator hydrator;
    private final Connection base;
    private final Connection working;

    JdbcSelectedCanonicalBase(Connection base, Connection working, JdbcPublicationHydrator hydrator) {
        this.base = base; this.working = working; this.hydrator = hydrator;
    }

    void metadata(String artifact) throws SQLException {
        for (String table : List.of("artifact_identity", "canonical_match_definition", "artifact_revision", "artifact_projection_state")) {
            hydrator.copy(table, "b.artifact=?", List.of(artifact));
        }
    }

    void rows(String artifact) throws SQLException {
        List<Object> parameters = List.of(artifact);
        hydrator.copy(artifact, "b.id IN(SELECT row_id FROM temp.publication_selected_row WHERE artifact=?)", parameters);
        hydrator.copy(artifact + "_sources", "b.row_id IN(SELECT row_id FROM temp.publication_selected_row WHERE artifact=?)", parameters);
        hydrator.copy("canonical_match_alias", "b.artifact=? AND b.canonical_row_id IN(SELECT row_id"
                + " FROM temp.publication_selected_row WHERE artifact=?)", List.of(artifact, artifact));
        hydrator.copy("registered_observation", "b.occurrence_id IN(SELECT o.occurrence_id FROM"
                + " canonical_lifecycle_field_origin o CROSS JOIN temp.publication_selected_row r"
                + " ON r.artifact=o.artifact AND r.lifecycle_id=o.lifecycle_id WHERE r.artifact=?)", parameters);
        hydrator.copy("canonical_lifecycle_field_origin", "b.artifact=? AND b.lifecycle_id IN(SELECT lifecycle_id"
                + " FROM temp.publication_selected_row WHERE artifact=?)", List.of(artifact, artifact));
    }

    void registration(String occurrence) throws SQLException {
        if (occurrence != null) { hydrator.copy("registered_observation", "b.occurrence_id=?", List.of(occurrence)); }
    }

    void authorityMetadata() throws SQLException {
        // These are singleton control facts, not a copy of mutable canonical business tables.
        try (var statement = working.createStatement()) {
            statement.executeUpdate("DELETE FROM canonical_lifecycle_control");
            statement.executeUpdate("DELETE FROM observation_order_control");
        }
        hydrator.copy("canonical_lifecycle_control", "b.singleton_id=1", List.of());
        hydrator.copy("observation_order_control", "b.singleton_id=1", List.of());
    }

    void ordinaryReceipt(String observation, String receipt, List<DataframeArtifactSchema> schemas) throws SQLException {
        hydrator.copy("canonical_observation", "b.observation_id=?", List.of(observation));
        hydrator.copy("canonical_observation_commit", "b.observation_id=?", List.of(observation));
        for (String table : List.of("confirmation_receipt", "confirmation_receipt_artifact", "confirmation_receipt_field_position")) {
            hydrator.copy(table, "b.receipt_id=?", List.of(receipt));
        }
        for (var schema : schemas) { hydrator.copy(schema.artifactName() + "_receipt_rows", "b.receipt_id=?", List.of(receipt)); }
    }

    void slotNamespace(String profile, String artifact) throws SQLException {
        for (String table : List.of("export_slot_assignment", "export_slot_state", "export_slot_free_range")) {
            hydrator.copy(table, "b.profile=? AND b.artifact=?", List.of(profile, artifact));
        }
    }

    Connection base() { return base; }
    Connection working() { return working; }

    void copy(String table, String predicate, List<Object> parameters) throws SQLException {
        hydrator.copy(table, predicate, parameters);
    }
}
