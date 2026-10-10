package com.iocextractor.adapter.out.store.jdbc;

import com.iocextractor.application.artifact.ArtifactIdentityDefinition;
import com.iocextractor.application.artifact.CanonicalKeyMaterial;
import com.iocextractor.application.artifact.lifecycle.EffectiveTime;
import com.iocextractor.application.dataframeimport.model.ImportPromotionOutcome;
import com.iocextractor.application.port.out.dataframeimport.CanonicalImportCommand;
import com.iocextractor.application.port.out.dataframeimport.CanonicalImportResult;
import com.iocextractor.application.port.out.dataframeimport.CanonicalImportWriter;
import com.iocextractor.common.IocExtractorException;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;

/** Delivery-wide publication around the existing integrity check, import planner, mutation kernel and slot policy. */
final class JdbcStagedImportWriter implements CanonicalImportWriter {
    private final JdbcStagedCanonicalBackend backend;
    private final JdbcCanonicalImportWriter reader;

    JdbcStagedImportWriter(JdbcStagedCanonicalBackend backend) {
        this.backend = backend;
        reader = backend.nativeImport(backend.configuration().dataSource(), EffectiveTime.at(Instant.EPOCH), backend.readContext());
    }

    @Override public CanonicalImportResult promote(CanonicalImportCommand command) {
        String identity = "import:" + command.deliveryId().value();
        String pins = ArtifactIdentityDefinition.sha256(backend.configuration().fingerprint() + "\n" + command.sequence()
                + "\n" + command.sourceId() + "\n" + command.snapshot() + "\n" + command.contract()
                + "\n" + command.stage() + "\n" + command.registration());
        try {
            boolean publishedReceipt = backend.publication().committed(
                    new JdbcStagedPublication.Unit(identity, pins, java.util.Set.of("import-receipt"), true));
            var prior = reader.loadCommitted(command);
            if (prior.isPresent()) { return result(prior.orElseThrow(), false); }
            if (publishedReceipt) { throw new IocExtractorException("Committed publication lacks its native import receipt"); }
            var input = reader.inspectPreparationInput(command);
            var scopes = new LinkedHashSet<String>();
            scopes.add("import-receipt");
            input.branchCounts().keySet().forEach(artifact -> scopes.add("artifact:" + artifact));
            input.slotProfile().ifPresent(profile -> input.branchCounts().keySet().forEach(artifact -> scopes.add("slot:" + profile + ":" + artifact)));
            var unit = new JdbcStagedPublication.Unit(identity, pins, scopes, true);
            boolean published = backend.publication().execute(unit, task(command, input));
            var committed = reader.loadCommitted(command).orElseThrow(() -> new IocExtractorException("Committed publication lacks its native import receipt"));
            return result(committed, published);
        } catch (SQLException failure) { throw new IocExtractorException("Cannot prepare staged import", failure); }
    }

    private static CanonicalImportResult result(CanonicalImportResult result, boolean published) {
        return new CanonicalImportResult(published ? ImportPromotionOutcome.COMMITTED : ImportPromotionOutcome.ALREADY_COMMITTED,
                result.acceptedRows(), result.rejectedRows(), result.publicMutations(), result.affectedArtifacts(), result.observedArtifacts(),
                result.projectionGenerations(), result.effectiveAt());
    }

    private JdbcStagedPublication.NativeTask task(CanonicalImportCommand command, JdbcCanonicalImportWriter.PreparationInput input) {
        return new JdbcStagedPublication.NativeTask() {
            public void hydrate(JdbcPublicationSelection selection, JdbcSelectedCanonicalBase base, JdbcPublicationSlotBase slots, EffectiveTime asOf)
                    throws SQLException {
                try (var stage = DriverManager.getConnection("jdbc:sqlite:" + input.path().toUri().toASCIIString() + "?mode=ro&immutable=1")) {
                    JdbcImportStageReadPolicy.configure(stage, "main", backend.configuration().workspaces().canonicalReadCacheKiB());
                    requests(stage, selection);
                }
                base.registration(command.registration() == null ? null : command.registration().observationId().value());
                for (String artifact : input.branchCounts().keySet()) {
                    selection.select(artifact, asOf.value().toEpochMilli());
                    base.metadata(artifact); base.rows(artifact);
                    if (input.slotProfile().isPresent()) {
                        base.slotNamespace(input.slotProfile().orElseThrow(), artifact);
                        slots.hydrate(artifact, asOf.value().toEpochMilli());
                    }
                }
            }
            public void prepare(DataSource working, JdbcCanonicalPreparationContext context, EffectiveTime asOf) throws SQLException {
                backend.reservePrivateIds(working, input.branchCounts());
                backend.nativeImport(working, asOf, context).promote(command);
            }
        };
    }

    private static void requests(Connection stage, JdbcPublicationSelection selection) throws SQLException {
        String admitted = " FROM stage_branch b JOIN stage_input_row r ON r.source_row_number=b.source_row_number"
                + " WHERE b.status='ACCEPTED' AND r.status='ACCEPTED'";
        try (var statement = stage.createStatement(); var rows = statement.executeQuery("SELECT b.artifact,b.record_key_hash" + admitted)) {
            while (rows.next()) { selection.request(rows.getString(1), rows.getString(2), List.of()); }
        }
        try (var statement = stage.createStatement(); var rows = statement.executeQuery("SELECT b.artifact,k.definition_id,k.key_hash,k.key_canonical"
                + " FROM stage_match_key k JOIN stage_branch b ON b.branch_id=k.branch_id"
                + " JOIN stage_input_row r ON r.source_row_number=b.source_row_number WHERE b.status='ACCEPTED' AND r.status='ACCEPTED'")) {
            while (rows.next()) { selection.match(rows.getString(1), new CanonicalKeyMaterial(rows.getString(2), rows.getString(3), rows.getString(4))); }
        }
    }
}
