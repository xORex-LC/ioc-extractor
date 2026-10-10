package com.iocextractor.adapter.out.store.jdbc;

import com.iocextractor.application.artifact.lifecycle.EffectiveTime;
import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/** Reuses native control operations inside the same fenced visibility protocol as promotions. */
final class JdbcStagedControl {
    private final JdbcStagedCanonicalBackend backend;
    JdbcStagedControl(JdbcStagedCanonicalBackend backend) { this.backend = backend; }

    <R> R execute(Set<String> scopes, JdbcWriterAdmission.OperationClass operation, Hydration hydration, Mutation<R> mutation) {
        var result = new AtomicReference<R>();
        var unit = new JdbcStagedPublication.Unit("control:" + UUID.randomUUID(), backend.configuration().fingerprint(), scopes, false, operation);
        backend.publication().execute(unit, new JdbcStagedPublication.NativeTask() {
            public void hydrate(JdbcPublicationSelection selection, JdbcSelectedCanonicalBase base,
                                JdbcPublicationSlotBase slots, EffectiveTime asOf) throws SQLException {
                hydration.copy(selection, base, slots);
            }
            public void prepare(DataSource working, JdbcCanonicalPreparationContext context, EffectiveTime asOf) throws SQLException {
                result.set(mutation.apply(working, context, asOf));
            }
        });
        return result.get();
    }

    @FunctionalInterface interface Hydration {
        void copy(JdbcPublicationSelection selection, JdbcSelectedCanonicalBase base, JdbcPublicationSlotBase slots) throws SQLException;
    }
    @FunctionalInterface interface Mutation<R> {
        R apply(DataSource working, JdbcCanonicalPreparationContext context, EffectiveTime asOf) throws SQLException;
    }
}
