package com.iocextractor.adapter.out.store.jdbc;

import com.iocextractor.application.artifact.lifecycle.EffectiveTime;
import com.iocextractor.application.port.out.artifact.lifecycle.LifecycleHistoryStore;
import java.util.List;
import java.util.Set;

/** Native history retention, with cascades captured and made visible together. */
final class JdbcStagedHistoryStore implements LifecycleHistoryStore {
    private final JdbcStagedCanonicalBackend backend;
    JdbcStagedHistoryStore(JdbcStagedCanonicalBackend backend) { this.backend = backend; }

    public HistoryPurgeResult purge(String artifact, EffectiveTime cutoff, int batchSize) {
        backend.requireArtifact(artifact);
        if (batchSize < 1 || batchSize == Integer.MAX_VALUE) { throw new IllegalArgumentException("Invalid history batch size"); }
        return new JdbcStagedControl(backend).execute(Set.of("artifact:" + artifact), JdbcWriterAdmission.OperationClass.MAINTENANCE,
                (selection, base, slots) -> {
                    String history = JdbcSql.quote(artifact + "_history");
                    base.copy(artifact + "_history", "b.history_id IN(SELECT history_id FROM " + history
                            + " WHERE closed_at_epoch_ms<=? ORDER BY closed_at_epoch_ms,history_id LIMIT ?)",
                            List.of(cutoff.value().toEpochMilli(), batchSize + 1));
                    String selected = "SELECT _lifecycle_id FROM " + history + " WHERE closed_at_epoch_ms<=?"
                            + " ORDER BY closed_at_epoch_ms,history_id LIMIT ?";
                    base.copy(artifact + "_history_sources", "b.history_id IN(SELECT history_id FROM " + history
                            + " WHERE closed_at_epoch_ms<=? ORDER BY closed_at_epoch_ms,history_id LIMIT ?)",
                            List.of(cutoff.value().toEpochMilli(), batchSize + 1));
                    base.copy("registered_observation", "b.occurrence_id IN(SELECT occurrence_id FROM canonical_lifecycle_field_origin_history"
                            + " WHERE artifact=? AND lifecycle_id IN(" + selected + "))", List.of(artifact, cutoff.value().toEpochMilli(), batchSize + 1));
                    base.copy("canonical_lifecycle_field_origin_history", "b.artifact=? AND b.lifecycle_id IN(" + selected + ")",
                            List.of(artifact, cutoff.value().toEpochMilli(), batchSize + 1));
                }, (working, context, asOf) -> new JdbcLifecycleHistoryStore(working, backend.configuration().schemas()).purge(artifact, cutoff, batchSize));
    }
}
