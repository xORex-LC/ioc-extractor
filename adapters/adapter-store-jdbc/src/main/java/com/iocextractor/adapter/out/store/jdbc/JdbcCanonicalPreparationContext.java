package com.iocextractor.adapter.out.store.jdbc;

import java.util.Objects;

/** Identity authority and effect capture for one native preparation strategy. */
record JdbcCanonicalPreparationContext(JdbcCanonicalReservationAuthority reservations,
                                      JdbcMutationEffectSink.Factory effects) {
    JdbcCanonicalPreparationContext {
        Objects.requireNonNull(reservations, "reservations");
        Objects.requireNonNull(effects, "effects");
    }

    JdbcCanonicalPreparationContext(JdbcCanonicalReservationAuthority reservations) {
        this(reservations, JdbcMutationEffectSink.Factory.NONE);
    }
}
