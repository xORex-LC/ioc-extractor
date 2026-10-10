package com.iocextractor.adapter.out.store.jdbc;

import com.iocextractor.application.artifact.lifecycle.EffectiveTime;
import com.iocextractor.application.artifact.lifecycle.ExpiryBatchResult;
import com.iocextractor.application.artifact.lifecycle.LifecycleDeadline;
import com.iocextractor.application.port.out.artifact.lifecycle.ExpiredArtifactStore;
import com.iocextractor.common.IocExtractorException;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Indexed committed deadlines and native bounded archive/delete through typed publication. */
final class JdbcStagedExpiryStore implements ExpiredArtifactStore {
    private final JdbcStagedCanonicalBackend backend;
    private final JdbcStagedControl control;
    JdbcStagedExpiryStore(JdbcStagedCanonicalBackend backend) { this.backend = backend; control = new JdbcStagedControl(backend); }

    public Optional<LifecycleDeadline> nearestDeadline() {
        long nearest = Long.MAX_VALUE;
        try (var c = backend.configuration().dataSource().getConnection()) {
            c.setAutoCommit(false);
            for (var table : backend.store().tables()) {
                if (!backend.store().artifacts().contains(table.name())) { continue; }
                try (var s = c.prepareStatement(JdbcTypedVersionTable.nearestSql(table))) {
                    s.setLong(1, Long.MIN_VALUE); s.setLong(2, Long.MIN_VALUE);
                    try (var rows = s.executeQuery()) {
                        if (rows.next() && rows.getObject(1) != null) { nearest = Math.min(nearest, rows.getLong(1)); }
                    }
                }
            }
            c.rollback();
        } catch (SQLException failure) { throw new IocExtractorException("Cannot read staged lifecycle deadlines", failure); }
        return nearest == Long.MAX_VALUE ? Optional.empty() : Optional.of(new LifecycleDeadline(Instant.ofEpochMilli(nearest)));
    }

    public ExpiryBatchResult expireDue(String artifact, EffectiveTime cycle, int batchSize) {
        backend.requireArtifact(artifact);
        if (batchSize < 1 || batchSize == Integer.MAX_VALUE) { throw new IllegalArgumentException("Invalid expiry batch size"); }
        return control.execute(Set.of("artifact:" + artifact), JdbcWriterAdmission.OperationClass.EXPIRY,
                (selection, base, slots) -> {
                    var table = backend.store().tables().stream().filter(candidate -> candidate.name().equals(artifact)).findFirst().orElseThrow();
                    String sql = "INSERT INTO temp.publication_selected_row SELECT ?,id,_lifecycle_id FROM("
                            + JdbcTypedVersionTable.dueSql(table) + ")";
                    try (var s = base.base().prepareStatement(sql)) {
                        s.setString(1, artifact); s.setLong(2, cycle.value().toEpochMilli());
                        s.setLong(3, cycle.value().toEpochMilli()); s.setInt(4, batchSize + 1); s.executeUpdate();
                    }
                    base.metadata(artifact); base.rows(artifact);
                }, (working, context, asOf) -> {
                    backend.reservePrivateIds(working, Map.of(artifact, batchSize));
                    return new JdbcExpiredArtifactStore(working, backend.configuration().schemas()).expireDue(artifact, cycle, batchSize);
                });
    }
}
