package com.iocextractor.adapter.out.store.jdbc;

import com.iocextractor.application.dataframeimport.model.ImportExistingSlotPolicy;

import java.util.Objects;

/** One transaction-local preferred export-slot request from canonical promotion. */
record PreferredExportSlotRequest(
        long lifecycleId,
        long requestedSlot,
        boolean newLifecycle,
        ImportExistingSlotPolicy existingRecordPolicy) {

    PreferredExportSlotRequest {
        if (lifecycleId < 1) {
            throw new IllegalArgumentException("Preferred export-slot lifecycle ID must be positive");
        }
        if (requestedSlot < 1 || requestedSlot > JdbcExportSlotRegistry.MAX_ASSIGNABLE_SLOT) {
            throw new IllegalArgumentException("Preferred export slot is outside the supported positive range");
        }
        Objects.requireNonNull(existingRecordPolicy, "existingRecordPolicy");
    }
}
