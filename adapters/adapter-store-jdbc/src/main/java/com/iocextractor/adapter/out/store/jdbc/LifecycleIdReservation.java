package com.iocextractor.adapter.out.store.jdbc;

import com.iocextractor.application.artifact.lifecycle.LifecycleId;

/** Durable, ascending lifecycle-id range reserved before canonical work. */
record LifecycleIdReservation(long start, int count) {

    LifecycleIdReservation {
        if (start <= 0) {
            throw new IllegalArgumentException("Lifecycle reservation start must be positive");
        }
        if (count < 0) {
            throw new IllegalArgumentException("Lifecycle reservation count must not be negative");
        }
        if (count > 0 && start > Long.MAX_VALUE - (count - 1L)) {
            throw new IllegalArgumentException("Lifecycle reservation exceeds the id range");
        }
    }

    LifecycleId idAt(int offset) {
        if (offset < 0 || offset >= count) {
            throw new IndexOutOfBoundsException(offset);
        }
        return new LifecycleId(Math.addExact(start, offset));
    }
}
