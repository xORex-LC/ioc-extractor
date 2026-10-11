package com.iocextractor.adapter.out.store.jdbc;

import com.iocextractor.application.artifact.ArtifactIdentityDefinition;
import com.iocextractor.application.artifact.CanonicalArtifactKeyResolver;
import com.iocextractor.application.artifact.lifecycle.CanonicalArtifactConfirmation;
import com.iocextractor.application.artifact.lifecycle.EffectiveTime;
import com.iocextractor.application.artifact.lifecycle.LifecycleWriteResult;
import com.iocextractor.application.port.out.artifact.lifecycle.CanonicalArtifactWriter;
import com.iocextractor.common.IocExtractorException;
import javax.sql.DataSource;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Map;
import java.util.Set;

/** Artifact-scoped publication; the native writer remains the only ordinary mutation and receipt engine. */
final class JdbcStagedConfirmationWriter implements CanonicalArtifactWriter {
    private final JdbcStagedCanonicalBackend backend;
    private final JdbcCanonicalLifecycleWriter reader;
    private final CanonicalArtifactKeyResolver keys;

    JdbcStagedConfirmationWriter(JdbcStagedCanonicalBackend backend) {
        this.backend = backend;
        reader = backend.nativeConfirmation(backend.configuration().dataSource(), EffectiveTime.at(Instant.EPOCH), backend.readContext());
        keys = new CanonicalArtifactKeyResolver(backend.configuration().identities());
    }

    @Override public LifecycleWriteResult confirm(CanonicalArtifactConfirmation command) {
        String identity = "document:" + ArtifactIdentityDefinition.sha256(command.observationId().value() + "\n" + command.artifactName());
        String pins = ArtifactIdentityDefinition.sha256(backend.configuration().fingerprint() + "\n" + command.sourceKey()
                + "\n" + command.receipt() + "\n" + command.registration() + "\n" + command.header() + "\n" + command.records().size());
        var unit = new JdbcStagedPublication.Unit(identity, pins,
                Set.of("artifact:" + command.artifactName(), "confirmation-receipt", "canonical-observation"), true);
        try {
            backend.publication().committed(unit);
            var prior = reader.loadCommitted(command);
            if (prior.isPresent()) { return prior.orElseThrow(); }
            boolean published = backend.publication().execute(unit, task(command));
            var result = reader.loadCommitted(command).orElseThrow(() -> new IocExtractorException("Committed publication lacks its native confirmation marker"));
            return new LifecycleWriteResult(result.observationId(), result.artifactName(), result.effectiveTime(), result.created(),
                    result.renewed(), result.restarted(), result.publicRowsUpdated(), result.metadataOnlyRows(), result.artifactRevision(),
                    result.requiredProjectionGeneration(), !published);
        } catch (SQLException failure) { throw new IocExtractorException("Cannot validate confirmation publication", failure); }
    }

    private JdbcStagedPublication.NativeTask task(CanonicalArtifactConfirmation command) {
        return new JdbcStagedPublication.NativeTask() {
            private JdbcConfirmationReceiptBase receiptBase = JdbcConfirmationReceiptBase.EMPTY;
            public void hydrate(JdbcPublicationSelection selection, JdbcSelectedCanonicalBase base, JdbcPublicationSlotBase slots, EffectiveTime asOf)
                    throws SQLException {
                String artifact = command.artifactName();
                try (var rows = command.records().open()) {
                    while (rows.next()) {
                        var record = rows.value();
                        var row = record.preparedRow().template();
                        String rowKey = keys.recordKeyOf(artifact, row).map(key -> key.keyHash()).orElse(record.rowKey().value());
                        selection.request(artifact, rowKey, keys.matchKeysOf(artifact, row));
                    }
                }
                selection.select(artifact, asOf.value().toEpochMilli());
                base.metadata(artifact);
                base.registration(command.registration() == null ? null : command.registration().observationId().value());
                base.rows(artifact);
                receiptBase = base.ordinaryReceipt(command.observationId().value(), command.receipt().id().value(), backend.configuration().schemas());
            }
            public void prepare(DataSource working, JdbcCanonicalPreparationContext context, EffectiveTime asOf) throws SQLException {
                backend.reservePrivateIds(working, Map.of(command.artifactName(), command.records().size()));
                backend.nativeConfirmation(working, asOf, context.withReceiptBase(receiptBase)).confirm(command);
            }
        };
    }
}
