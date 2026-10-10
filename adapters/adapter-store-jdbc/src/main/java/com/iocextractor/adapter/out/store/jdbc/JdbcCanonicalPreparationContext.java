package com.iocextractor.adapter.out.store.jdbc;

import java.util.Objects;

/** Identity authority and effect capture for one native preparation strategy. */
record JdbcCanonicalPreparationContext(JdbcCanonicalReservationAuthority reservations,
                                      JdbcMutationEffectSink.Factory effects,
                                      JdbcExportSlotRegistry exportSlots,
                                      int readCacheKiB) {
    JdbcCanonicalPreparationContext {
        Objects.requireNonNull(reservations, "reservations");
        Objects.requireNonNull(effects, "effects");
        Objects.requireNonNull(exportSlots, "exportSlots");
        if (readCacheKiB < 0) { throw new IllegalArgumentException("Invalid native stage read cache bound"); }
    }

    JdbcCanonicalPreparationContext(JdbcCanonicalReservationAuthority reservations, JdbcMutationEffectSink.Factory effects,
                                    JdbcExportSlotRegistry exportSlots) {
        this(reservations, effects, exportSlots, 0);
    }

    JdbcCanonicalPreparationContext(JdbcCanonicalReservationAuthority reservations, JdbcMutationEffectSink.Factory effects) {
        this(reservations, effects, new JdbcExportSlotRegistry());
    }

    JdbcCanonicalPreparationContext(JdbcCanonicalReservationAuthority reservations) {
        this(reservations, JdbcMutationEffectSink.Factory.NONE, new JdbcExportSlotRegistry());
    }
}
