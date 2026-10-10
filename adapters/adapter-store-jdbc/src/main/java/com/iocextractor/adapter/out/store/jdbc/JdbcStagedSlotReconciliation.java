package com.iocextractor.adapter.out.store.jdbc;

import com.iocextractor.application.export.ExportPlan;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashSet;

/** Runs the existing sparse reusable-slot allocator in a private native namespace. */
final class JdbcStagedSlotReconciliation implements JdbcSnapshotSliceReader.SlotReconciliation {
    private final JdbcStagedCanonicalBackend backend;
    JdbcStagedSlotReconciliation(JdbcStagedCanonicalBackend backend) { this.backend = backend; }
    public void reconcile(ExportPlan plan, Instant asOf) throws SQLException {
        var scopes = new LinkedHashSet<String>();
        for (var spec : plan.artifacts()) {
            backend.requireArtifact(spec.artifactName());
            scopes.add("artifact:" + spec.artifactName());
            scopes.add("slot:" + plan.profile().name() + ":" + spec.artifactName());
        }
        new JdbcStagedControl(backend).execute(scopes, JdbcWriterAdmission.OperationClass.EXPORT_SLOTS,
                (selection, base, slots) -> {
                    for (var spec : plan.artifacts()) {
                        base.metadata(spec.artifactName());
                        if (spec.columns().contains("id")) {
                            base.slotNamespace(plan.profile().name(), spec.artifactName());
                            slots.hydrate(spec.artifactName(), asOf.toEpochMilli());
                        }
                    }
                }, (working, context, preparedAt) -> {
                    try (var c = working.getConnection()) {
                        c.setAutoCommit(false);
                        try {
                            context.exportSlots().reconcile(c, plan, asOf);
                            c.commit();
                        } catch (SQLException | RuntimeException | Error failure) {
                            JdbcLifecycleTransactions.rollback(c, failure); throw failure;
                        }
                    }
                    return null;
                });
    }
}
