package com.iocextractor.adapter.out.store.jdbc;

import com.iocextractor.application.artifact.lifecycle.ConfirmationReceiptSnapshot;
import com.iocextractor.application.artifact.lifecycle.EffectiveTime;
import com.iocextractor.application.artifact.lifecycle.ObservationId;
import com.iocextractor.application.port.out.artifact.lifecycle.CanonicalObservationStore;
import com.iocextractor.application.port.out.artifact.lifecycle.ConfirmationReceiptStore;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Complete native receipt replay and bounded retention over the same committed visibility. */
final class JdbcStagedReceiptStore implements ConfirmationReceiptStore, CanonicalObservationStore {
    private final JdbcStagedCanonicalBackend backend;
    private final Duration stagingRetention;
    private final JdbcConfirmationReceiptStore reader;
    JdbcStagedReceiptStore(JdbcStagedCanonicalBackend backend, Duration stagingRetention) {
        this.backend = backend; this.stagingRetention = stagingRetention;
        reader = new JdbcConfirmationReceiptStore(backend.configuration().dataSource(), backend.configuration().schemas(), stagingRetention);
    }
    public Optional<ConfirmationReceiptSnapshot> findComplete(String source, String fingerprint, EffectiveTime asOf) {
        return reader.findComplete(source, fingerprint, asOf);
    }
    public void markTerminal(ObservationId observation, EffectiveTime completedAt, Duration retention) {
        new JdbcStagedControl(backend).execute(Set.of("canonical-observation"), JdbcWriterAdmission.OperationClass.CONTROL,
                (selection, base, slots) -> base.copy("canonical_observation", "b.observation_id=?", List.of(observation.value())),
                (working, context, asOf) -> {
                    new JdbcConfirmationReceiptStore(working, backend.configuration().schemas(), stagingRetention)
                            .markTerminal(observation, completedAt, retention);
                    return null;
                });
    }
    public PurgeResult purgeExpired(EffectiveTime asOf, int batchSize) {
        if (batchSize < 1 || batchSize == Integer.MAX_VALUE) { throw new IllegalArgumentException("Invalid receipt batch size"); }
        return new JdbcStagedControl(backend).execute(Set.of("confirmation-receipt", "canonical-observation"),
                JdbcWriterAdmission.OperationClass.MAINTENANCE,
                (selection, base, slots) -> hydrateRetention(base, asOf, batchSize + 1),
                (working, context, preparedAt) -> new JdbcConfirmationReceiptStore(working, backend.configuration().schemas(), stagingRetention)
                        .purgeExpired(asOf, batchSize));
    }
    private void hydrateRetention(JdbcSelectedCanonicalBase base, EffectiveTime asOf, int limit) throws SQLException {
        String selected = "SELECT r.receipt_id FROM confirmation_receipt r LEFT JOIN confirmation_receipt_artifact a"
                + " ON a.receipt_id=r.receipt_id WHERE (r.state='COMPLETE' AND r.purge_after_ms<=?)"
                + " OR(r.state='STAGING' AND a.staged_at_ms<=?) GROUP BY r.receipt_id"
                + " ORDER BY COALESCE(r.purge_after_ms,MIN(a.staged_at_ms)),r.receipt_id LIMIT ?";
        List<Object> parameters = List.of(asOf.value().toEpochMilli(), asOf.value().minus(stagingRetention).toEpochMilli(), limit);
        for (String table : List.of("confirmation_receipt", "confirmation_receipt_artifact")) {
            base.copy(table, "b.receipt_id IN(" + selected + ")", parameters);
        }
        if (backend.store().receiptPayloads() == JdbcReceiptPayloadLayout.VERSIONED) {
            base.copy("confirmation_receipt_field_position", "b.receipt_id IN(" + selected + ")", parameters);
            for (var schema : backend.configuration().schemas()) {
                base.copy(schema.artifactName() + "_receipt_rows", "b.receipt_id IN(" + selected + ")", parameters);
            }
        }
        String observations = "SELECT observation_id FROM canonical_observation WHERE state='TERMINAL' AND purge_after_ms<=?"
                + " ORDER BY purge_after_ms,observation_id LIMIT ?";
        List<Object> observationParameters = List.of(asOf.value().toEpochMilli(), limit);
        base.copy("canonical_observation", "b.observation_id IN(" + observations + ")", observationParameters);
        base.copy("canonical_observation_commit", "b.observation_id IN(" + observations + ")", observationParameters);
    }
}
